package com.truecost.api.dto;

/**
 * The trip level identifying data both legs contributed. Carries a separate route hash and
 * bucket for each leg rather than a single pair, since a trip prices two independent legs,
 * outbound and return.
 */
public record TripContext(
        int rentalDays,
        double totalDistanceMiles,
        String tollClass,
        String outboundRouteHash,
        String returnRouteHash,
        String departureBucket,
        String returnBucket,
        String dateBucket) {
}
