package dev.pbmlab.claims.domain;

import java.math.BigDecimal;
import java.util.List;

/** What goes back to the pharmacy (simplified B1 response), plus the trace for learning. */
public record ClaimResponse(String claimId, ClaimStatus status, String rejectCode, String rejectMessage,
                            BigDecimal allowedAmount, BigDecimal patientPay, BigDecimal planPay,
                            boolean duplicate, List<String> trace) {

    public static ClaimResponse from(Claim c, boolean duplicate) {
        return new ClaimResponse(c.claimId(), c.status(), c.rejectCode(), c.rejectMessage(),
                c.allowedAmount(), c.patientPay(), c.planPay(), duplicate, c.trace());
    }
}
