package dev.pbmlab.claims.domain;

import java.math.BigDecimal;

/** A pharmacy and its network contract: brand discount off AWP and a dispensing fee. */
public record Pharmacy(String pharmacyId, String name, boolean inNetwork,
                       BigDecimal brandDiscountPct, BigDecimal dispensingFee) {
}
