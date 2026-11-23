package dev.pbmlab.claims.domain;

/**
 * A covered drug. Tier drives the copay; paRequired and maxQtyPer30Days are utilization-management edits.
 * NDCs in this lab are fictional.
 */
public record FormularyEntry(String ndc, String drugName, int tier, boolean generic,
                             boolean paRequired, int maxQtyPer30Days) {
}
