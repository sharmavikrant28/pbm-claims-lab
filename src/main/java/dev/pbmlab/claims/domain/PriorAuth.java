package dev.pbmlab.claims.domain;

import java.time.LocalDate;

/** An approved prior authorization for one member and one drug, valid for a date range. */
public record PriorAuth(String memberId, String ndc, LocalDate validFrom, LocalDate validTo) {
    public boolean covers(String memberId, String ndc, LocalDate date) {
        return this.memberId.equals(memberId) && this.ndc.equals(ndc)
                && !date.isBefore(validFrom) && !date.isAfter(validTo);
    }
}
