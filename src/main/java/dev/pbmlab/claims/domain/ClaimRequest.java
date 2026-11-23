package dev.pbmlab.claims.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A simplified NCPDP B1 (billing) request. Real B1 messages carry many more segments and fields.
 * usualAndCustomary is the pharmacy's cash price for this fill.
 */
public record ClaimRequest(String pharmacyId, String rxNumber, int fillNumber, String memberId,
                           String ndc, int quantity, int daysSupply, LocalDate dateOfService,
                           BigDecimal usualAndCustomary) {

    /** Same pharmacy + Rx + fill + date of service = the same claim. Used to stop double payment on retries. */
    public String idempotencyKey() {
        return pharmacyId + "|" + rxNumber + "|" + fillNumber + "|" + dateOfService;
    }
}
