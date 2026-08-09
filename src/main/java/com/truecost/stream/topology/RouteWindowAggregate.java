package com.truecost.stream.topology;

import com.truecost.events.SearchRequested;

/**
 * The windowed aggregate the hot route topology accumulates per route key and date bucket. A
 * plain count would not let the topology build a self contained HotRouteSignal, so this also
 * keeps the most recent search's coordinates, instants, and car class as representative
 * parameters, stored as epoch milliseconds rather than Instant so the aggregate serializes as
 * plain JSON in the changelog with no time module wiring.
 */
public record RouteWindowAggregate(
        long searchCount,
        double originLat,
        double originLng,
        double destLat,
        double destLng,
        long departureAt,
        long returnAt,
        String carClass,
        String pickupLocationCode,
        String destinationCode) {

    public static RouteWindowAggregate empty() {
        return new RouteWindowAggregate(0L, 0.0, 0.0, 0.0, 0.0, 0L, 0L, null, null, null);
    }

    public RouteWindowAggregate add(SearchRequested search) {
        return new RouteWindowAggregate(
                searchCount + 1,
                search.getOriginLat(),
                search.getOriginLng(),
                search.getDestLat(),
                search.getDestLng(),
                search.getDepartureAt().toEpochMilli(),
                search.getReturnAt().toEpochMilli(),
                search.getCarClass(),
                search.getPickupLocationCode(),
                search.getDestinationCode());
    }
}
