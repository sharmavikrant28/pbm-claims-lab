package dev.pbmlab.claims.engine;

import dev.pbmlab.claims.domain.DrugPrice;
import dev.pbmlab.claims.domain.FormularyEntry;
import dev.pbmlab.claims.domain.Pharmacy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Rule R7: lesser-of pricing, rounded to cents half-up. */
class PricingCalculatorTest {

    private final PricingCalculator calc = new PricingCalculator();
    private final Pharmacy pharmacy = new Pharmacy("PH", "Test", true, new BigDecimal("0.15"), new BigDecimal("1.50"));

    private static FormularyEntry drug(boolean generic) {
        return new FormularyEntry("N", "Drug", 1, generic, false, 30);
    }

    @Test
    void genericWithMacUsesMacTimesQuantityPlusFee() {
        var r = calc.price(drug(true), new DrugPrice("N", new BigDecimal("1.20"), new BigDecimal("0.10")),
                pharmacy, 30, null);
        assertEquals(new BigDecimal("3.00"), r.ingredientCost());
        assertEquals(new BigDecimal("1.50"), r.dispensingFee());
        assertEquals(new BigDecimal("4.50"), r.contractPrice());
        assertEquals(new BigDecimal("4.50"), r.allowedAmount());
        assertEquals("MAC", r.basis());
    }

    @Test
    void brandUsesAwpMinusDiscount() {
        var r = calc.price(drug(false), new DrugPrice("N", new BigDecimal("12.00"), null), pharmacy, 30, null);
        assertEquals(new BigDecimal("306.00"), r.ingredientCost()); // 12 x 30 x 0.85
        assertEquals(new BigDecimal("307.50"), r.contractPrice());
        assertEquals("AWP minus 15%", r.basis());
    }

    @Test
    void genericWithoutMacFallsBackToAwp() {
        var r = calc.price(drug(true), new DrugPrice("N", new BigDecimal("2.00"), null), pharmacy, 10, null);
        assertEquals(new BigDecimal("17.00"), r.ingredientCost());
        assertEquals("AWP minus 15%", r.basis());
    }

    @Test
    void ingredientCostRoundsHalfUp() {
        // 1.235 x 1 x 0.85 = 1.04975 -> 1.05
        var r = calc.price(drug(false), new DrugPrice("N", new BigDecimal("1.235"), null), pharmacy, 1, null);
        assertEquals(new BigDecimal("1.05"), r.ingredientCost());
    }

    @Test
    void usualAndCustomaryWinsOnlyWhenStrictlyLower() {
        DrugPrice price = new DrugPrice("N", new BigDecimal("1.20"), new BigDecimal("0.10"));
        var lower = calc.price(drug(true), price, pharmacy, 30, new BigDecimal("3.00"));
        assertEquals(new BigDecimal("3.00"), lower.allowedAmount());
        assertEquals("U&C (cash price was lower)", lower.basis());

        var equal = calc.price(drug(true), price, pharmacy, 30, new BigDecimal("4.50"));
        assertEquals(new BigDecimal("4.50"), equal.allowedAmount());
        assertEquals("MAC", equal.basis());
    }
}
