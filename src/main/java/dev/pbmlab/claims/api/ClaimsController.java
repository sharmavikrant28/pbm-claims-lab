package dev.pbmlab.claims.api;

import dev.pbmlab.claims.domain.Accumulator;
import dev.pbmlab.claims.domain.ClaimRequest;
import dev.pbmlab.claims.domain.ClaimResponse;
import dev.pbmlab.claims.domain.OutboxEvent;
import dev.pbmlab.claims.engine.AdjudicationEngine;
import dev.pbmlab.claims.store.AccumulatorStore;
import dev.pbmlab.claims.store.ClaimStore;
import dev.pbmlab.claims.store.OutboxStore;
import dev.pbmlab.claims.store.ReferenceData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "Claims", description = "Adjudication, reversal, accumulators, outbox and reference data")
@RestController
@RequestMapping("/api")
public class ClaimsController {

    private final AdjudicationEngine engine;
    private final ClaimStore claims;
    private final AccumulatorStore accumulators;
    private final OutboxStore outbox;
    private final ReferenceData ref;

    public ClaimsController(AdjudicationEngine engine, ClaimStore claims, AccumulatorStore accumulators,
                            OutboxStore outbox, ReferenceData ref) {
        this.engine = engine;
        this.claims = claims;
        this.accumulators = accumulators;
        this.outbox = outbox;
        this.ref = ref;
    }

    /** Simplified NCPDP B1: bill a claim. */
    @Operation(summary = "Submit a claim (B1)",
            description = "Adjudicates a claim. Paid and rejected claims both return 200; check `status` and "
                    + "`rejectCode`. Resending the same pharmacy + Rx + fill + date of service returns the original "
                    + "paid claim with `duplicate=true`.")
    @ApiResponse(responseCode = "200", description = "Adjudicated (PAID or REJECTED)")
    @ApiResponse(responseCode = "400", description = "Missing required fields or non-positive quantity/daysSupply",
            content = @Content)
    @PostMapping("/claims")
    public ClaimResponse submit(@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
            mediaType = "application/json",
            examples = {
                    @ExampleObject(name = "Generic - pays at MAC", value = """
                            {"pharmacyId":"PH-001","rxNumber":"RX1001","fillNumber":0,"memberId":"M1001",
                             "ndc":"11111-2222-01","quantity":30,"daysSupply":30,"dateOfService":"2026-12-01",
                             "usualAndCustomary":12.00}"""),
                    @ExampleObject(name = "Brand - deductible then $40 copay", value = """
                            {"pharmacyId":"PH-001","rxNumber":"RX1002","fillNumber":0,"memberId":"M1001",
                             "ndc":"22222-3333-01","quantity":30,"daysSupply":30,"dateOfService":"2026-12-01",
                             "usualAndCustomary":400.00}"""),
                    @ExampleObject(name = "Specialty with prior auth", value = """
                            {"pharmacyId":"PH-001","rxNumber":"RX1003","fillNumber":0,"memberId":"M1001",
                             "ndc":"33333-4444-01","quantity":2,"daysSupply":28,"dateOfService":"2026-12-01",
                             "usualAndCustomary":9000.00}"""),
                    @ExampleObject(name = "Reject 65 - terminated member", value = """
                            {"pharmacyId":"PH-001","rxNumber":"RX1005","fillNumber":0,"memberId":"M1002",
                             "ndc":"11111-2222-01","quantity":30,"daysSupply":30,"dateOfService":"2026-12-01",
                             "usualAndCustomary":12.00}"""),
                    @ExampleObject(name = "Reject 76 - quantity too high", value = """
                            {"pharmacyId":"PH-001","rxNumber":"RX1007","fillNumber":0,"memberId":"M1001",
                             "ndc":"11111-2222-01","quantity":90,"daysSupply":30,"dateOfService":"2026-12-01",
                             "usualAndCustomary":12.00}""")
            }))
                                @RequestBody ClaimRequest request) {
        return engine.adjudicate(request);
    }

    /** Simplified NCPDP B2: reverse a paid claim. */
    @Operation(summary = "Reverse a paid claim (B2)",
            description = "Takes the claim's amounts back out of the accumulators, marks it REVERSED, and frees "
                    + "its key so the same fill can be billed again.")
    @ApiResponse(responseCode = "200", description = "Reversed")
    @ApiResponse(responseCode = "400", description = "Unknown claimId", content = @Content)
    @ApiResponse(responseCode = "409", description = "Claim is not PAID (already reversed, or rejected)",
            content = @Content)
    @PostMapping("/claims/{claimId}/reversal")
    public ClaimResponse reverse(@PathVariable String claimId) {
        return engine.reverse(claimId);
    }

    @Operation(summary = "Get a claim by id")
    @ApiResponse(responseCode = "200", description = "Found")
    @ApiResponse(responseCode = "404", description = "No such claim", content = @Content)
    @GetMapping("/claims/{claimId}")
    public ResponseEntity<ClaimResponse> get(@PathVariable String claimId) {
        return claims.findById(claimId)
                .map(c -> ResponseEntity.ok(ClaimResponse.from(c, false)))
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Get a member's deductible and out-of-pocket totals for a plan year")
    @GetMapping("/members/{memberId}/accumulators/{planYear}")
    public Accumulator accumulators(@Parameter(example = "M1001") @PathVariable String memberId,
                                    @Parameter(example = "2026") @PathVariable int planYear) {
        return accumulators.get(memberId, planYear);
    }

    @Operation(summary = "List outbox events",
            description = "One event per decision (ClaimPaid, ClaimRejected, ClaimReversed). "
                    + "A background job marks them published every 2 seconds.")
    @GetMapping("/outbox")
    public List<OutboxEvent> outbox() {
        return outbox.all();
    }

    @Operation(summary = "Show seeded reference data",
            description = "Plans, members, pharmacies, formulary, prices and prior authorizations.")
    @GetMapping("/reference")
    public Map<String, Object> reference() {
        return ref.snapshot();
    }
}
