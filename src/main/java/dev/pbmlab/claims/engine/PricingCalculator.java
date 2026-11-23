package dev.pbmlab.claims.engine;

import dev.pbmlab.claims.domain.DrugPrice;
import dev.pbmlab.claims.domain.FormularyEntry;
import dev.pbmlab.claims.domain.Pharmacy;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * "Lesser-of" pricing, simplified:
 *   contract price = (generic with MAC ? MAC x qty : AWP x qty x (1 - brand discount)) + dispensing fee
 *   allowed amount = lower of contract price and the pharmacy's usual & customary (cash) price
 */
public class PricingCalculator {

    public record PricingResult(BigDecimal ingredientCost, BigDecimal dispensingFee,
                                BigDecimal contractPrice, BigDecimal allowedAmount, String basis) {
    }

    public PricingResult price(FormularyEntry drug, DrugPrice price, Pharmacy pharmacy,
                               int quantity, BigDecimal usualAndCustomary) {
        BigDecimal qty = BigDecimal.valueOf(quantity);
        BigDecimal ingredient;
        String basis;
        if (drug.generic() && price.macPerUnit() != null) {
            ingredient = price.macPerUnit().multiply(qty);
            basis = "MAC";
        } else {
            BigDecimal factor = BigDecimal.ONE.subtract(pharmacy.brandDiscountPct());
            ingredient = price.awpPerUnit().multiply(qty).multiply(factor);
            basis = "AWP minus " + pharmacy.brandDiscountPct().movePointRight(2).stripTrailingZeros().toPlainString() + "%";
        }
        ingredient = money(ingredient);
        BigDecimal fee = money(pharmacy.dispensingFee());
        BigDecimal contract = ingredient.add(fee);

        BigDecimal allowed = contract;
        if (usualAndCustomary != null && usualAndCustomary.compareTo(contract) < 0) {
            allowed = money(usualAndCustomary);
            basis = "U&C (cash price was lower)";
        }
        return new PricingResult(ingredient, fee, contract, allowed, basis);
    }

    static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
