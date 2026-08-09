package com.truecost.route;

import java.util.List;

/**
 * A driving route from OSRM, decoded into timed points. distanceMeters and durationSeconds are
 * the whole route totals, points is the polyline geometry with per point cumulative duration
 * used by crossing detection and toll timeline pricing.
 */
public record Route(List<RoutePoint> points, double distanceMeters, double durationSeconds) {
}
