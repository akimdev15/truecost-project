package com.truecost.toll;

/**
 * A crossing the detector matched against a route polyline, carrying enough of the match
 * evidence, matched point distance and vehicle bearing, for logging and debugging, plus the
 * cumulative route duration at the matched point that TollTimeline turns into an arrival
 * instant.
 */
public record DetectedCrossing(
        String crossingCode,
        String crossingName,
        String crossingType,
        double matchDistanceMeters,
        double vehicleBearingDeg,
        double cumulativeDurationSeconds) {
}
