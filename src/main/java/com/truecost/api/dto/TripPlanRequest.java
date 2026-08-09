package com.truecost.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * The POST /api/v1/trips/plan request, carrying both endpoint coordinates for OSRM and crossing
 * detection and provider location codes for the rental and hotel SPIs, since no coordinate to
 * code gazetteer exists yet. rentalRateOverrides null or empty falls back to the automatic
 * provider fan-out, while a non-empty list makes TripPlanner build RentalQuote objects directly
 * from these instead of calling any RentalQuoteProvider.
 */
public record TripPlanRequest(
        double originLat,
        double originLng,
        String pickupLocationCode,
        double destLat,
        double destLng,
        String destinationCode,
        Instant departureAt,
        Instant returnAt,
        boolean hasPersonalEzpass,
        String carClass,
        List<RentalRateOverride> rentalRateOverrides) {
}
