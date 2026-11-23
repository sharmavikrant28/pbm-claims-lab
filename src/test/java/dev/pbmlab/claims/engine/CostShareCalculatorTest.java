package dev.pbmlab.claims.engine;

import dev.pbmlab.claims.domain.Accumulator;
import dev.pbmlab.claims.domain.Plan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Rule R8: deductible first, then tier copay, capped at the out-of-pocket max. */
class CostShareCalculatorTest {

    private final CostShareCalculator calc = new CostShareCalculator();
    private final Plan plan = new Plan("P", new BigDecimal("100.00"), new BigDecimal("1000.00"),
            Map.of(1, new BigDecimal("10.00"), 2, new BigDecimal("40.00"), 4, new BigDecimal("250.00")));

    private static Accumulator acc(String deductibleMet, String oopMet) {
        return new Accumulator("M", 2026, new BigDecimal(deductibleMet), new BigDecimal(oopMet), 0);
    }

    private static BigDecimal m(String v) {
        return new BigDecimal(v);
    }

    @Test
    void deductibleThenCopay() {
        var s = calc.split(m("307.50"), plan, 2, acc("0.00", "0.00"));
        assertEquals(m("140.00"), s.patientPay());
        assertEquals(m("167.50"), s.planPay());
        assertEquals(m("100.00"), s.deductibleApplied());
    }

    @Test
    void allowedBelowRemainingDeductibleIsAllPatient() {
        var s = calc.split(m("4.50"), plan, 1, acc("0.00", "0.00"));
        assertEquals(m("4.50"), s.patientPay());
        assertEquals(m("0.00"), s.planPay());
        assertEquals(m("4.50"), s.deductibleApplied());
    }

    @Test
    void copayNeverExceedsWhatRemainsAfterDeductible() {
        var s = calc.split(m("4.50"), plan, 1, acc("100.00", "100.00"));
        assertEquals(m("4.50"), s.patientPay()); // copay 10.00 capped to 4.50
        assertEquals(m("0.00"), s.planPay());
        assertEquals(m("0.00"), s.deductibleApplied());
    }

    @Test
    void unknownTierHasNoCopay() {
        var s = calc.split(m("50.00"), plan, 3, acc("100.00", "100.00"));
        assertEquals(m("0.00"), s.patientPay());
        assertEquals(m("50.00"), s.planPay());
    }

    @Test
    void outOfPocketMaxCapsPatientPay() {
        var s = calc.split(m("5101.50"), plan, 4, acc("100.00", "900.00"));
        assertEquals(m("100.00"), s.patientPay());
        assertEquals(m("5001.50"), s.planPay());
        assertEquals(m("0.00"), s.deductibleApplied());
    }

    @Test
    void outOfPocketCapAlsoLimitsDeductibleApplied() {
        var s = calc.split(m("5101.50"), plan, 4, acc("0.00", "950.00"));
        assertEquals(m("50.00"), s.patientPay());
        assertEquals(m("50.00"), s.deductibleApplied());
        assertEquals(m("5051.50"), s.planPay());
    }

    @Test
    void oopAlreadyMetMeansPlanPaysAll() {
        var s = calc.split(m("307.50"), plan, 2, acc("100.00", "1000.00"));
        assertEquals(m("0.00"), s.patientPay());
        assertEquals(m("307.50"), s.planPay());
    }
}
