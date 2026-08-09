package com.truecost.api.dto;

import java.util.List;

/**
 * The winning toll strategy for one rental option. kind holds the Strategy enum name,
 * PERSONAL_TAG, COMPANY_PER_CROSSING, COMPANY_UNLIMITED, or NO_ARRANGEMENT, and candidates
 * preserves every evaluated candidate in the engine's own ranking order.
 */
public record ChosenStrategy(
        String kind,
        String programName,
        long totalCents,
        String explanation,
        List<StrategyCandidate> candidates) {
}
