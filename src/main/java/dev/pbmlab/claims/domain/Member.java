package dev.pbmlab.claims.domain;

import java.time.LocalDate;

/** A covered person. Coverage is effective-dated: a claim is judged on its date of service. */
public record Member(String memberId, String name, String planId, LocalDate coverageStart, LocalDate coverageEnd) {
    public boolean isCoveredOn(LocalDate date) {
        return !date.isBefore(coverageStart) && !date.isAfter(coverageEnd);
    }
}
