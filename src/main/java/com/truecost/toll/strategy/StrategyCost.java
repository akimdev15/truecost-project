package com.truecost.toll.strategy;

/**
 * The fully broken down cost of one evaluated candidate. totalCents is feeCents plus tollCents
 * plus congestionCents, with each component zeroed out when inapplicable or already covered by
 * the plan, and feeDays records the day count the fee was computed over so tests can verify the
 * derivation.
 */
public record StrategyCost(
        Strategy strategy,
        String programName,
        long totalCents,
        long feeCents,
        long tollCents,
        long congestionCents,
        int feeDays) {
}
