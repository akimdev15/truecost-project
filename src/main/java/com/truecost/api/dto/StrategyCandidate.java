package com.truecost.api.dto;

/**
 * A field for field copy of one com.truecost.toll.strategy.StrategyCost, so the API surfaces
 * every runner up the strategy engine already retained without recomputing anything.
 */
public record StrategyCandidate(
        String strategy,
        String programName,
        long totalCents,
        long feeCents,
        long tollCents,
        long congestionCents,
        int feeDays) {
}
