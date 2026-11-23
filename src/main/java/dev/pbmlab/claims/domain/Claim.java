package dev.pbmlab.claims.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The adjudicated claim, including a decision trace so anyone can later see why it paid or rejected.
 * Records are immutable: a reversal creates a new Claim value with status REVERSED.
 */
public record Claim(String claimId, String idempotencyKey, ClaimRequest request, ClaimStatus status,
                    String rejectCode, String rejectMessage,
                    BigDecimal allowedAmount, BigDecimal patientPay, BigDecimal planPay,
                    BigDecimal deductibleApplied, int planYear,
                    List<String> trace, Instant adjudicatedAt) {

    public Claim withStatus(ClaimStatus newStatus, List<String> newTrace) {
        return new Claim(claimId, idempotencyKey, request, newStatus, rejectCode, rejectMessage,
                allowedAmount, patientPay, planPay, deductibleApplied, planYear, newTrace, adjudicatedAt);
    }
}
