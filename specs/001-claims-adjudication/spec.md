# Spec 001: Real-time claims adjudication (lab scope)

Status: implemented in this repo. Written in the Spec-Driven Development style: business rules and
acceptance criteria first, then the plan and code.

## Goal

Given a simplified NCPDP B1 claim, decide PAID or REJECTED, price it, split the cost between member and plan,
and record everything needed to explain the decision later.

## Business rules

1. **R1 Idempotency.** A claim is identified by pharmacyId + rxNumber + fillNumber + dateOfService.
   If a PAID claim with that key exists, return it unchanged with `duplicate=true`. Rejected claims do not hold the key.
2. **R2 Network.** The pharmacy must exist and be in network, else reject 50.
3. **R3 Eligibility.** The member must be covered on the date of service, else reject 65.
4. **R4 Formulary.** The NDC must be on the formulary with a price, else reject 70.
5. **R5 Quantity limit.** Allowed quantity = ceil(maxQtyPer30Days x daysSupply / 30). Above that, reject 76.
6. **R6 Prior authorization.** If the drug requires PA, an approved PA must cover the member, NDC and date of service, else reject 75.
7. **R7 Pricing (lesser-of).** Contract price = (generic with MAC: MAC x qty; otherwise AWP x qty x (1 - brand discount)) + dispensing fee.
   Allowed = min(contract price, usual & customary). Round to cents, half up.
8. **R8 Cost share.** Member pays toward remaining deductible first, then the tier copay (not more than what remains),
   with the yearly total capped at the out-of-pocket maximum. Plan pays the rest.
9. **R9 Accumulators.** Deductible and out-of-pocket totals per member per plan year are updated in the same unit of work
   as the claim, using optimistic locking with up to 3 retries.
10. **R10 Events.** Every decision writes an outbox event (ClaimPaid, ClaimRejected, ClaimReversed).
11. **R11 Reversal (B2).** Only PAID claims can be reversed. Reversal subtracts the claim's accumulator effects,
    marks it REVERSED and frees the idempotency key.

## Acceptance criteria (see AdjudicationEngineTest)

- Generic 30 tablets at MAC $0.10 with $1.50 fee: allowed $4.50, member pays $4.50 (deductible), plan $0.00.
- Cash price $3.00 below contract price: allowed $3.00.
- Same claim submitted twice: second response is a duplicate with the same claimId; accumulators counted once.
- Terminated member: 65. Unknown NDC: 70. 90 tablets for 30 days of a 30-per-30 drug: 76. Specialty after PA expiry: 75.
- Six specialty fills: member's out-of-pocket total stops at $1,000.00.
- Reversal brings accumulators back to zero and the same fill can be billed again.

## Out of scope

Real NCPDP message parsing, COB, DUR, refill-too-soon, compounds, Medicare Part D phases, multiple plan versions.
