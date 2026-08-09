package com.truecost.api.dto;

/**
 * The rank one option. savingsOverNextCents is the rank two option's total minus the rank one
 * total, or 0 when there is only a single option.
 */
public record Recommendation(
        String companyCode,
        String carClassCode,
        long trueTotalCents,
        long savingsOverNextCents,
        String summary) {
}
