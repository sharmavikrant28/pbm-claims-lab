# PBM Claims Lab

A small, runnable **learning prototype** of pharmacy (PBM) claims adjudication in Java 21 and Spring Boot 3.
It's simplified on purpose: real NCPDP messages, benefit designs and pricing contracts are far richer.
All members, drugs and NDCs are fictional.

## What it does

`POST /api/claims` takes a simplified NCPDP **B1** (billing) claim and runs these checks in order:

| Step | Check | Reject code |
| --- | --- | --- |
| 0 | Idempotency: same pharmacy + Rx + fill + date already paid? Return the original answer | (duplicate) |
| 1 | Pharmacy in network | 50 |
| 2 | Member covered on the **date of service** (effective dating) | 65 |
| 3 | Drug (NDC) on the formulary | 70 |
| 4 | Quantity within plan limit for the days supply | 76 |
| 5 | Prior authorization on file, if the drug needs one | 75 |
| 6 | **Lesser-of pricing**: contract price (MAC for generics, AWP minus discount for brands, plus dispensing fee) vs the pharmacy's cash price |  |
| 7 | **Cost share**: deductible, then tier copay, capped at out-of-pocket max; accumulators updated with optimistic locking |  |
| 8 | Claim, accumulator and a `ClaimPaid` event saved together (**transactional outbox**); a relay publishes events |  |

`POST /api/claims/{id}/reversal` is a simplified **B2**: it undoes the accumulators and frees the key so the fill can be billed again.

## Run it

Requirements: **Java 21** and **Maven 3.9+** (or open the folder in IntelliJ IDEA, which bundles Maven).

```bash
mvn test              # unit tests: engine, pricing, cost share, stores
mvn verify            # unit tests + API integration tests (*IT, full app on a random port)
mvn spring-boot:run   # starts on http://localhost:8080
```

Swagger UI: http://localhost:8080/swagger-ui.html (OpenAPI JSON at `/v3/api-docs`). "Try it out" on
`POST /api/claims` has ready-made example claims in the Examples dropdown.

Or open `requests.http` in IntelliJ (or VS Code with the REST Client extension) and run the requests top to bottom.
With curl:

```bash
curl -s -X POST localhost:8080/api/claims -H 'Content-Type: application/json' -d '{
  "pharmacyId":"PH-001","rxNumber":"RX1001","fillNumber":0,"memberId":"M1001",
  "ndc":"11111-2222-01","quantity":30,"daysSupply":30,
  "dateOfService":"2026-12-01","usualAndCustomary":12.00}'
```

Every response includes a `trace` array showing each decision. Read it: that's the point of the lab.

## Seeded data

| Type | Data |
| --- | --- |
| Plan | PLAN-GOLD: deductible $100, out-of-pocket max $1,000, copays tier 1 $10, tier 2 $40, tier 3 $80, tier 4 $250 |
| Members | M1001 covered 2026-01-01 to 2027-12-31; M1002 terminated 2026-03-31 |
| Pharmacies | PH-001 in network (brand AWP minus 15%, $1.50 fee); PH-999 out of network |
| Drugs | 11111-2222-01 generic statin (tier 1, MAC $0.10/unit); 22222-3333-01 brand statin (tier 2, AWP $12/unit); 33333-4444-01 specialty pen (tier 4, AWP $3,000/unit, PA required, max 2 per 30 days); 99999-0000-01 not covered |
| Prior auth | M1001 for the specialty pen, valid 2026-01-01 to 2027-06-30 |

## Worked example (from the tests)

1. Generic, 30 tablets: 30 x $0.10 + $1.50 = **$4.50** allowed. The member pays all of it, because it goes to the $100 deductible.
2. Brand, 30 tablets: 30 x $12 x 0.85 + $1.50 = **$307.50**. The member pays the remaining $95.50 deductible + $40 copay = **$135.50**; the plan pays $172.00.
3. Specialty fills: $250 copay each until the member's yearly total hits **$1,000**; after that the member pays $0.

## Project layout

```
src/main/java/dev/pbmlab/claims/
  domain/   records: ClaimRequest, Claim, Member, Plan, FormularyEntry, DrugPrice, Pharmacy, PriorAuth, Accumulator, OutboxEvent
  engine/   AdjudicationEngine (the checks), PricingCalculator (lesser-of), CostShareCalculator (deductible, copay, OOP max)
  store/    in-memory stores: ReferenceData (seed), ClaimStore (idempotency), AccumulatorStore (optimistic locking), OutboxStore
  api/      REST controller, error handler, OutboxRelay (scheduled publisher)
  config/   Spring wiring
specs/      the Spec-Driven Development spec this was built from
docs/       architecture notes and a PostgreSQL schema for the next step
```

The engine is plain Java with no Spring code, so the business rules are easy to read and test.

## Ideas to extend it (good practice)

1. Replace the in-memory stores with PostgreSQL using `docs/schema.sql` (unique index for idempotency, version column for accumulators).
2. Publish outbox events to Kafka, keyed by memberId.
3. Add reject 79 (refill too soon) using the member's fill history.
4. Make plans effective-dated (a new plan version each year).
5. Add a DUR warning for two drugs that interact.
