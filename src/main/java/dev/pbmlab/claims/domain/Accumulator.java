package dev.pbmlab.claims.domain;

import java.math.BigDecimal;

/**
 * Running totals for one member and plan year. version supports optimistic locking:
 * an update only succeeds if nobody changed the row since we read it.
 */
public record Accumulator(String memberId, int planYear, BigDecimal deductibleMet,
                          BigDecimal outOfPocketMet, long version) {

    public static Accumulator empty(String memberId, int planYear) {
        return new Accumulator(memberId, planYear, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), 0);
    }

    public Accumulator add(BigDecimal deductible, BigDecimal oop) {
        return new Accumulator(memberId, planYear, deductibleMet.add(deductible), outOfPocketMet.add(oop), version + 1);
    }
}
