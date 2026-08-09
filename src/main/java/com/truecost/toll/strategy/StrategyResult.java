package com.truecost.toll.strategy;

import java.util.List;

/**
 * The toll strategy engine's answer for one rental option. programName is null for the personal
 * tag and no-arrangement strategies, and candidates retains every evaluated option, including
 * runners up, for the API and tests to inspect.
 */
public record StrategyResult(
        Strategy winner,
        String programName,
        long totalCents,
        String explanation,
        List<StrategyCost> candidates) {
}
