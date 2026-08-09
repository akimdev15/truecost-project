package com.truecost.route;

/**
 * The cumulative duration from route origin to this point is what lets TollTimeline turn a
 * detected crossing here into an estimated arrival instant, departure time plus this offset.
 */
public record RoutePoint(double lat, double lng, double cumulativeDurationSeconds) {
}
