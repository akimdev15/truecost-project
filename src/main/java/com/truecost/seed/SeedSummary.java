package com.truecost.seed;

/** Row counts written by one seed run, logged by the caller once loading finishes. */
public record SeedSummary(
        int vehicleClasses,
        int crossings,
        int tollRates,
        int congestionSchedules,
        int congestionCredits,
        int rentalCompanies,
        int tollPrograms) {
}
