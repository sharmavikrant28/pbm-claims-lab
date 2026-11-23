package dev.pbmlab.claims.domain;

import java.math.BigDecimal;

/** Price benchmarks per unit. AWP is a list price; MAC is the PBM's cap for generics (null for brands). */
public record DrugPrice(String ndc, BigDecimal awpPerUnit, BigDecimal macPerUnit) {
}
