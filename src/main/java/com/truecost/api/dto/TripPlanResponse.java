package com.truecost.api.dto;

import java.util.List;

/**
 * The POST /api/v1/trips/plan response, with options sorted ascending by trueTotalCents, ties
 * broken by companyCode then carClassCode. recommendation is null only when options is empty,
 * meaning every rental provider was Absent.
 */
public record TripPlanResponse(
        List<TripOption> options,
        Recommendation recommendation,
        TripContext context,
        SharedFreshness freshness) {
}
