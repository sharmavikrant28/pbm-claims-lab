package dev.pbmlab.claims.engine;

import dev.pbmlab.claims.domain.Accumulator;
import dev.pbmlab.claims.domain.Plan;

import java.math.BigDecimal;

/**
 * Splits the allowed amount between member and plan:
 *   1. Member pays toward any remaining deductible.
 *   2. Then the tier copay (never more than what's left).
 *   3. Member's total for the year is capped at the out-of-pocket maximum.
 */
public class CostShareCalculator {

    public record CostShare(BigDecimal patientPay, BigDecimal planPay, BigDecimal deductibleApplied) {
    }

    public CostShare split(BigDecimal allowed, Plan plan, int tier, Accumulator acc) {
        BigDecimal deductibleRemaining = nonNegative(plan.deductible().subtract(acc.deductibleMet()));
        BigDecimal deductiblePortion = allowed.min(deductibleRemaining);
        BigDecimal afterDeductible = allowed.subtract(deductiblePortion);
        BigDecimal copay = plan.copayFor(tier).min(afterDeductible);

        BigDecimal patient = deductiblePortion.add(copay);
        BigDecimal deductibleApplied = deductiblePortion;

        BigDecimal oopRemaining = nonNegative(plan.outOfPocketMax().subtract(acc.outOfPocketMet()));
        if (patient.compareTo(oopRemaining) > 0) {
            patient = oopRemaining;
            deductibleApplied = deductiblePortion.min(patient);
        }
        BigDecimal planPay = allowed.subtract(patient);
        return new CostShare(PricingCalculator.money(patient), PricingCalculator.money(planPay),
                PricingCalculator.money(deductibleApplied));
    }

    private static BigDecimal nonNegative(BigDecimal v) {
        return v.signum() < 0 ? BigDecimal.ZERO : v;
    }
}
