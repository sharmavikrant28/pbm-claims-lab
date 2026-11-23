package dev.pbmlab.claims.engine;

import dev.pbmlab.claims.domain.*;
import dev.pbmlab.claims.store.AccumulatorStore;
import dev.pbmlab.claims.store.ClaimStore;
import dev.pbmlab.claims.store.OutboxStore;
import dev.pbmlab.claims.store.ReferenceData;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The real-time claims path. Checks run in this order, and the first failure rejects the claim:
 *   0. Idempotency: same claim already paid? return the original answer.
 *   1. Pharmacy in network?            (reject 50)
 *   2. Member covered on date of service? (reject 65)
 *   3. Drug on the formulary?          (reject 70)
 *   4. Quantity within plan limits?    (reject 76)
 *   5. Prior authorization on file if required? (reject 75)
 *   6. Price it (lesser-of), split cost with accumulators, save claim + accumulator + outbox event together.
 *
 * Plain Java on purpose: no framework code, so the business rules are easy to read and unit test.
 */
public class AdjudicationEngine {

    private static final int MAX_ACCUMULATOR_RETRIES = 3;

    private final ReferenceData ref;
    private final ClaimStore claims;
    private final AccumulatorStore accumulators;
    private final OutboxStore outbox;
    private final Clock clock;
    private final PricingCalculator pricing = new PricingCalculator();
    private final CostShareCalculator costShare = new CostShareCalculator();
    private final Map<String, ReentrantLock> memberLocks = new ConcurrentHashMap<>();

    public AdjudicationEngine(ReferenceData ref, ClaimStore claims, AccumulatorStore accumulators,
                              OutboxStore outbox, Clock clock) {
        this.ref = ref;
        this.claims = claims;
        this.accumulators = accumulators;
        this.outbox = outbox;
        this.clock = clock;
    }

    public ClaimResponse adjudicate(ClaimRequest req) {
        validate(req);
        // One member's claims are processed one at a time, so two pharmacies can't
        // both spend the same deductible. (Optimistic locking below is the second safety net.)
        ReentrantLock lock = memberLocks.computeIfAbsent(req.memberId(), k -> new ReentrantLock());
        lock.lock();
        try {
            return doAdjudicate(req);
        } finally {
            lock.unlock();
        }
    }

    private ClaimResponse doAdjudicate(ClaimRequest req) {
        List<String> trace = new ArrayList<>();
        String key = req.idempotencyKey();

        var existing = claims.findPaidByKey(key);
        if (existing.isPresent()) {
            return ClaimResponse.from(existing.get(), true);
        }
        trace.add("0. Idempotency key " + key + " is new");

        LocalDate dos = req.dateOfService();

        var pharmacy = ref.pharmacy(req.pharmacyId()).filter(Pharmacy::inNetwork);
        if (pharmacy.isEmpty()) {
            return reject(req, RejectCode.NON_MATCHED_PHARMACY, trace, "1. Pharmacy " + req.pharmacyId() + " not in network");
        }
        trace.add("1. Pharmacy " + req.pharmacyId() + " is in network");

        var member = ref.member(req.memberId()).filter(m -> m.isCoveredOn(dos));
        var plan = member.flatMap(m -> ref.plan(m.planId()));
        if (member.isEmpty() || plan.isEmpty()) {
            return reject(req, RejectCode.PATIENT_NOT_COVERED, trace, "2. Member " + req.memberId() + " not covered on " + dos);
        }
        trace.add("2. Member covered on " + dos + " under " + plan.get().planId());

        var drug = ref.formulary(req.ndc());
        var price = ref.price(req.ndc());
        if (drug.isEmpty() || price.isEmpty()) {
            return reject(req, RejectCode.PRODUCT_NOT_COVERED, trace, "3. NDC " + req.ndc() + " not on formulary");
        }
        FormularyEntry d = drug.get();
        trace.add("3. On formulary: " + d.drugName() + ", tier " + d.tier());

        int allowedQty = (int) Math.ceil(d.maxQtyPer30Days() * req.daysSupply() / 30.0);
        if (req.quantity() > allowedQty) {
            return reject(req, RejectCode.PLAN_LIMITATIONS_EXCEEDED, trace,
                    "4. Quantity " + req.quantity() + " exceeds limit " + allowedQty + " for " + req.daysSupply() + " days");
        }
        trace.add("4. Quantity " + req.quantity() + " within limit " + allowedQty);

        if (d.paRequired() && !ref.hasPriorAuth(req.memberId(), req.ndc(), dos)) {
            return reject(req, RejectCode.PRIOR_AUTH_REQUIRED, trace, "5. Prior authorization required and none on file");
        }
        trace.add(d.paRequired() ? "5. Prior authorization found" : "5. No prior authorization needed");

        var p = pricing.price(d, price.get(), pharmacy.get(), req.quantity(), req.usualAndCustomary());
        trace.add("6. Priced: ingredient " + p.ingredientCost() + " + fee " + p.dispensingFee()
                + " = contract " + p.contractPrice() + "; allowed " + p.allowedAmount() + " (" + p.basis() + ")");

        int planYear = dos.getYear();
        CostShareCalculator.CostShare share = null;
        for (int attempt = 1; attempt <= MAX_ACCUMULATOR_RETRIES; attempt++) {
            Accumulator acc = accumulators.get(req.memberId(), planYear);
            share = costShare.split(p.allowedAmount(), plan.get(), d.tier(), acc);
            Accumulator updated = acc.add(share.deductibleApplied(), share.patientPay());
            if (accumulators.compareAndSet(updated, acc.version())) {
                trace.add("7. Accumulators " + planYear + ": deductible met " + acc.deductibleMet() + " -> "
                        + updated.deductibleMet() + ", out-of-pocket " + acc.outOfPocketMet() + " -> " + updated.outOfPocketMet());
                break;
            }
            trace.add("7. Accumulator changed concurrently, retry " + attempt);
            if (attempt == MAX_ACCUMULATOR_RETRIES) {
                throw new IllegalStateException("Could not update accumulator after retries");
            }
        }

        Claim claim = new Claim(UUID.randomUUID().toString(), key, req, ClaimStatus.PAID, null, null,
                p.allowedAmount(), share.patientPay(), share.planPay(), share.deductibleApplied(),
                planYear, List.copyOf(trace), clock.instant());
        claims.save(claim);
        claims.registerPaidKey(key, claim.claimId());
        outbox.append("ClaimPaid", claim.claimId(), req.memberId(),
                "{\"claimId\":\"" + claim.claimId() + "\",\"ndc\":\"" + req.ndc() + "\",\"planPay\":" + share.planPay()
                        + ",\"patientPay\":" + share.patientPay() + "}");
        return ClaimResponse.from(claim, false);
    }

    /** B2 reversal: undo the money and the accumulators, and free the key so the fill can be billed again. */
    public ClaimResponse reverse(String claimId) {
        Claim original = claims.findById(claimId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown claim " + claimId));
        ReentrantLock lock = memberLocks.computeIfAbsent(original.request().memberId(), k -> new ReentrantLock());
        lock.lock();
        try {
            Claim current = claims.findById(claimId).orElseThrow();
            if (current.status() != ClaimStatus.PAID) {
                throw new IllegalStateException("Only PAID claims can be reversed; claim is " + current.status());
            }
            String memberId = current.request().memberId();
            for (int attempt = 1; attempt <= MAX_ACCUMULATOR_RETRIES; attempt++) {
                Accumulator acc = accumulators.get(memberId, current.planYear());
                Accumulator updated = acc.add(current.deductibleApplied().negate(), current.patientPay().negate());
                if (accumulators.compareAndSet(updated, acc.version())) {
                    break;
                }
                if (attempt == MAX_ACCUMULATOR_RETRIES) {
                    throw new IllegalStateException("Could not update accumulator after retries");
                }
            }
            List<String> trace = new ArrayList<>(current.trace());
            trace.add("B2 reversal: accumulators reduced by deductible " + current.deductibleApplied()
                    + " and out-of-pocket " + current.patientPay());
            Claim reversed = current.withStatus(ClaimStatus.REVERSED, List.copyOf(trace));
            claims.save(reversed);
            claims.releasePaidKey(current.idempotencyKey());
            outbox.append("ClaimReversed", claimId, memberId, "{\"claimId\":\"" + claimId + "\"}");
            return ClaimResponse.from(reversed, false);
        } finally {
            lock.unlock();
        }
    }

    private ClaimResponse reject(ClaimRequest req, RejectCode code, List<String> trace, String reason) {
        trace.add(reason + " -> REJECT " + code.code());
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        Claim claim = new Claim(UUID.randomUUID().toString(), req.idempotencyKey(), req, ClaimStatus.REJECTED,
                code.code(), code.message(), zero, zero, zero, zero, req.dateOfService().getYear(),
                List.copyOf(trace), clock.instant());
        claims.save(claim); // rejected claims are kept for audit but don't hold the idempotency key
        outbox.append("ClaimRejected", claim.claimId(), req.memberId(),
                "{\"claimId\":\"" + claim.claimId() + "\",\"rejectCode\":\"" + code.code() + "\"}");
        return ClaimResponse.from(claim, false);
    }

    private static void validate(ClaimRequest r) {
        if (r == null || r.pharmacyId() == null || r.rxNumber() == null || r.memberId() == null
                || r.ndc() == null || r.dateOfService() == null) {
            throw new IllegalArgumentException("pharmacyId, rxNumber, memberId, ndc and dateOfService are required");
        }
        if (r.quantity() <= 0 || r.daysSupply() <= 0) {
            throw new IllegalArgumentException("quantity and daysSupply must be positive");
        }
    }
}
