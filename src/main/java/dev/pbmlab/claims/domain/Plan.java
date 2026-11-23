package dev.pbmlab.claims.domain;

import java.math.BigDecimal;
import java.util.Map;

/** Benefit design: what the member pays. Real PBMs version these by effective date. */
public record Plan(String planId, BigDecimal deductible, BigDecimal outOfPocketMax, Map<Integer, BigDecimal> copayByTier) {
    public BigDecimal copayFor(int tier) {
        return copayByTier.getOrDefault(tier, BigDecimal.ZERO);
    }
}
