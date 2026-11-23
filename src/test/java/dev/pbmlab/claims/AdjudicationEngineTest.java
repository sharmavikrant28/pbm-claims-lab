package dev.pbmlab.claims;

import dev.pbmlab.claims.domain.ClaimRequest;
import dev.pbmlab.claims.domain.ClaimResponse;
import dev.pbmlab.claims.domain.ClaimStatus;
import dev.pbmlab.claims.domain.OutboxEvent;
import dev.pbmlab.claims.engine.AdjudicationEngine;
import dev.pbmlab.claims.store.AccumulatorStore;
import dev.pbmlab.claims.store.ClaimStore;
import dev.pbmlab.claims.store.OutboxStore;
import dev.pbmlab.claims.store.ReferenceData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Plain unit tests on the engine: no Spring context needed. */
class AdjudicationEngineTest {

    private static final LocalDate DOS = LocalDate.of(2026, 12, 1);
    private AdjudicationEngine engine;
    private AccumulatorStore accumulators;

    @BeforeEach
    void setUp() {
        accumulators = new AccumulatorStore();
        engine = new AdjudicationEngine(ReferenceData.seeded(), new ClaimStore(), accumulators,
                new OutboxStore(), Clock.systemUTC());
    }

    private static ClaimRequest claim(String member, String ndc, int qty, int days, String rx, String uc) {
        return new ClaimRequest("PH-001", rx, 0, member, ndc, qty, days, DOS, new BigDecimal(uc));
    }

    @Test
    void genericClaimPaysAtMacAndGoesToDeductible() {
        ClaimResponse r = engine.adjudicate(claim("M1001", "11111-2222-01", 30, 30, "RX1", "12.00"));
        assertEquals(ClaimStatus.PAID, r.status());
        assertEquals(new BigDecimal("4.50"), r.allowedAmount());   // 0.10 x 30 + 1.50 fee
        assertEquals(new BigDecimal("4.50"), r.patientPay());      // all toward the deductible
        assertEquals(new BigDecimal("0.00"), r.planPay());
    }

    @Test
    void cashPriceWinsWhenLower() {
        ClaimResponse r = engine.adjudicate(claim("M1001", "11111-2222-01", 30, 30, "RX2", "3.00"));
        assertEquals(new BigDecimal("3.00"), r.allowedAmount());
    }

    @Test
    void retryOfSameClaimIsNotPaidTwice() {
        ClaimResponse first = engine.adjudicate(claim("M1001", "22222-3333-01", 30, 30, "RX3", "400.00"));
        ClaimResponse retry = engine.adjudicate(claim("M1001", "22222-3333-01", 30, 30, "RX3", "400.00"));
        assertFalse(first.duplicate());
        assertTrue(retry.duplicate());
        assertEquals(first.claimId(), retry.claimId());
        assertEquals(first.patientPay(), accumulators.get("M1001", 2026).outOfPocketMet());
    }

    @Test
    void terminatedMemberRejects65() {
        ClaimResponse r = engine.adjudicate(claim("M1002", "11111-2222-01", 30, 30, "RX4", "12.00"));
        assertEquals("65", r.rejectCode());
    }

    @Test
    void drugNotOnFormularyRejects70() {
        ClaimResponse r = engine.adjudicate(claim("M1001", "99999-0000-01", 30, 30, "RX5", "12.00"));
        assertEquals("70", r.rejectCode());
    }

    @Test
    void tooManyUnitsRejects76() {
        ClaimResponse r = engine.adjudicate(claim("M1001", "11111-2222-01", 90, 30, "RX6", "12.00"));
        assertEquals("76", r.rejectCode());
    }

    @Test
    void specialtyWithoutPriorAuthRejects75() {
        ClaimRequest noPa = new ClaimRequest("PH-001", "RX7", 0, "M1001", "33333-4444-01", 2, 28,
                LocalDate.of(2027, 7, 1), new BigDecimal("9000.00")); // PA expired 2027-06-30
        assertEquals("75", engine.adjudicate(noPa).rejectCode());
    }

    @Test
    void outOfPocketMaxCapsMemberCost() {
        for (int i = 0; i < 6; i++) {
            engine.adjudicate(claim("M1001", "33333-4444-01", 2, 28, "RX-SP-" + i, "9000.00"));
        }
        assertEquals(new BigDecimal("1000.00"), accumulators.get("M1001", 2026).outOfPocketMet());
    }

    @Test
    void reversalUndoesAccumulatorsAndFreesTheKey() {
        ClaimResponse paid = engine.adjudicate(claim("M1001", "22222-3333-01", 30, 30, "RX8", "400.00"));
        ClaimResponse reversed = engine.reverse(paid.claimId());
        assertEquals(ClaimStatus.REVERSED, reversed.status());
        assertEquals(new BigDecimal("0.00"), accumulators.get("M1001", 2026).outOfPocketMet());
        ClaimResponse rebill = engine.adjudicate(claim("M1001", "22222-3333-01", 30, 30, "RX8", "400.00"));
        assertFalse(rebill.duplicate());
        assertEquals(ClaimStatus.PAID, rebill.status());
    }

    // ---- Rules not covered by the original acceptance criteria ----

    @Test
    void outOfNetworkPharmacyRejects50() {
        ClaimRequest r = new ClaimRequest("PH-999", "RX9", 0, "M1001", "11111-2222-01", 30, 30, DOS, new BigDecimal("12.00"));
        assertEquals("50", engine.adjudicate(r).rejectCode());
        ClaimRequest unknown = new ClaimRequest("PH-NOPE", "RX9", 0, "M1001", "11111-2222-01", 30, 30, DOS, null);
        assertEquals("50", engine.adjudicate(unknown).rejectCode());
    }

    @Test
    void unknownMemberRejects65() {
        assertEquals("65", engine.adjudicate(claim("M-NOPE", "11111-2222-01", 30, 30, "RX10", "12.00")).rejectCode());
    }

    @Test
    void networkCheckRunsBeforeEligibility() {
        ClaimRequest r = new ClaimRequest("PH-999", "RX11", 0, "M1002", "99999-0000-01", 90, 30, DOS, null);
        ClaimResponse resp = engine.adjudicate(r);
        assertEquals("50", resp.rejectCode());
        assertEquals(ClaimStatus.REJECTED, resp.status());
        assertEquals(new BigDecimal("0.00"), resp.planPay());
    }

    @Test
    void quantityLimitIsProratedByDaysSupply() {
        // 30 per 30 days -> 60 allowed for 60 days, 61 rejected
        assertEquals(ClaimStatus.PAID, engine.adjudicate(claim("M1001", "11111-2222-01", 60, 60, "RX12", "50.00")).status());
        assertEquals("76", engine.adjudicate(claim("M1001", "11111-2222-01", 61, 60, "RX13", "50.00")).rejectCode());
    }

    @Test
    void specialtyWithPriorAuthPaysDeductiblePlusSpecialtyCopay() {
        ClaimResponse r = engine.adjudicate(claim("M1001", "33333-4444-01", 2, 28, "RX14", "9000.00"));
        assertEquals(ClaimStatus.PAID, r.status());
        assertEquals(new BigDecimal("5101.50"), r.allowedAmount()); // 3000 x 2 x 0.85 + 1.50
        assertEquals(new BigDecimal("350.00"), r.patientPay());     // 100 deductible + 250 copay
        assertEquals(new BigDecimal("4751.50"), r.planPay());
    }

    @Test
    void rejectedClaimDoesNotHoldIdempotencyKey() {
        ClaimResponse rejected = engine.adjudicate(claim("M1001", "11111-2222-01", 90, 30, "RX15", "12.00"));
        assertEquals(ClaimStatus.REJECTED, rejected.status());
        ClaimResponse resubmit = engine.adjudicate(claim("M1001", "11111-2222-01", 90, 30, "RX15", "12.00"));
        assertFalse(resubmit.duplicate());
        assertNotEquals(rejected.claimId(), resubmit.claimId());
    }

    @Test
    void everyDecisionWritesAnOutboxEvent() {
        OutboxStore outbox = new OutboxStore();
        AdjudicationEngine e = new AdjudicationEngine(ReferenceData.seeded(), new ClaimStore(), new AccumulatorStore(),
                outbox, Clock.systemUTC());
        ClaimResponse paid = e.adjudicate(claim("M1001", "11111-2222-01", 30, 30, "RX16", "12.00"));
        e.adjudicate(claim("M1001", "99999-0000-01", 30, 30, "RX17", "12.00"));
        e.reverse(paid.claimId());
        e.adjudicate(claim("M1001", "11111-2222-01", 30, 30, "RX16", "12.00"));
        // duplicate after rebill must NOT add an event
        e.adjudicate(claim("M1001", "11111-2222-01", 30, 30, "RX16", "12.00"));

        assertEquals(List.of("ClaimPaid", "ClaimRejected", "ClaimReversed", "ClaimPaid"),
                outbox.all().stream().map(OutboxEvent::type).toList());
        assertTrue(outbox.all().get(0).payload().contains(paid.claimId()));
    }

    @Test
    void onlyPaidClaimsCanBeReversed() {
        ClaimResponse rejected = engine.adjudicate(claim("M1001", "99999-0000-01", 30, 30, "RX18", "12.00"));
        assertThrows(IllegalStateException.class, () -> engine.reverse(rejected.claimId()));

        ClaimResponse paid = engine.adjudicate(claim("M1001", "11111-2222-01", 30, 30, "RX19", "12.00"));
        engine.reverse(paid.claimId());
        assertThrows(IllegalStateException.class, () -> engine.reverse(paid.claimId()));
        assertThrows(IllegalArgumentException.class, () -> engine.reverse("no-such-claim"));
    }

    @Test
    void reversalRestoresDeductible() {
        ClaimResponse paid = engine.adjudicate(claim("M1001", "22222-3333-01", 30, 30, "RX20", "400.00"));
        assertEquals(new BigDecimal("100.00"), accumulators.get("M1001", 2026).deductibleMet());
        engine.reverse(paid.claimId());
        assertEquals(new BigDecimal("0.00"), accumulators.get("M1001", 2026).deductibleMet());
    }

    @Test
    void accumulatorsAreTrackedPerPlanYear() {
        engine.adjudicate(claim("M1001", "22222-3333-01", 30, 30, "RX21", "400.00"));
        ClaimRequest nextYear = new ClaimRequest("PH-001", "RX22", 0, "M1001", "22222-3333-01", 30, 30,
                LocalDate.of(2027, 2, 1), new BigDecimal("400.00"));
        ClaimResponse r = engine.adjudicate(nextYear);
        assertEquals(new BigDecimal("140.00"), r.patientPay()); // deductible applies again in the new year
        assertEquals(new BigDecimal("140.00"), accumulators.get("M1001", 2027).outOfPocketMet());
    }

    @Test
    void invalidRequestsAreRejectedWithIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> engine.adjudicate(null));
        assertThrows(IllegalArgumentException.class, () -> engine.adjudicate(
                new ClaimRequest("PH-001", "RX", 0, "M1001", null, 30, 30, DOS, null)));
        assertThrows(IllegalArgumentException.class, () -> engine.adjudicate(
                new ClaimRequest("PH-001", "RX", 0, "M1001", "11111-2222-01", 0, 30, DOS, null)));
        assertThrows(IllegalArgumentException.class, () -> engine.adjudicate(
                new ClaimRequest("PH-001", "RX", 0, "M1001", "11111-2222-01", 30, -1, DOS, null)));
    }

    @Test
    void concurrentClaimsForOneMemberSpendDeductibleExactlyOnce() throws Exception {
        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ClaimResponse>> results = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            String rx = "RX-C-" + i;
            results.add(pool.submit(() -> {
                start.await();
                return engine.adjudicate(claim("M1001", "22222-3333-01", 30, 30, rx, "400.00"));
            }));
        }
        start.countDown();
        BigDecimal totalPatient = BigDecimal.ZERO;
        for (Future<ClaimResponse> f : results) {
            ClaimResponse r = f.get(10, TimeUnit.SECONDS);
            assertEquals(ClaimStatus.PAID, r.status());
            totalPatient = totalPatient.add(r.patientPay());
        }
        pool.shutdown();
        // One claim pays 100 deductible + 40 copay; nine pay 40 copay.
        assertEquals(new BigDecimal("500.00"), totalPatient);
        assertEquals(new BigDecimal("500.00"), accumulators.get("M1001", 2026).outOfPocketMet());
        assertEquals(new BigDecimal("100.00"), accumulators.get("M1001", 2026).deductibleMet());
    }

    @Test
    void concurrentRetriesOfSameClaimPayOnce() throws Exception {
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ClaimResponse>> results = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return engine.adjudicate(claim("M1001", "22222-3333-01", 30, 30, "RX-SAME", "400.00"));
            }));
        }
        start.countDown();
        long originals = 0;
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Future<ClaimResponse> f : results) {
            ClaimResponse r = f.get(10, TimeUnit.SECONDS);
            ids.add(r.claimId());
            if (!r.duplicate()) originals++;
        }
        pool.shutdown();
        assertEquals(1, originals);
        assertEquals(1, ids.size());
        assertEquals(new BigDecimal("140.00"), accumulators.get("M1001", 2026).outOfPocketMet());
    }
}
