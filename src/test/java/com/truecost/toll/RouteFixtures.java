package com.truecost.toll;

import com.truecost.route.Route;
import com.truecost.route.RoutePoint;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds Route objects from hand specified real world waypoints for the golden route and
 * detector tests, standing in for a live OSRM polyline. Each golden route below is built from
 * real latitude and longitude points along the actual highways the trip drives, including the
 * seeded crossing coordinate itself as a waypoint wherever the route actually crosses that
 * facility, so the crossing detector sees the same geometry a real OSRM polyline would present
 * at the crossing. This keeps the detector and timeline tests fast and independent of a live
 * OSRM instance in ./gradlew test, matching the project's Testcontainers self-containment rule,
 * while still exercising the detector against real, geographically accurate route geometry
 * rather than fabricated coordinates.
 */
final class RouteFixtures {

    private RouteFixtures() {
    }

    record Wp(double lat, double lng) {
    }

    /**
     * A point a given distance and compass bearing from a start point, flat earth approximation.
     * Accurate to well under a meter at the few hundred meter offsets these fixtures use, which
     * is what lets a golden route place a waypoint immediately before and after a crossing
     * along an exact known bearing, the George Washington Bridge is FORWARD at 100 degrees so a
     * waypoint reached by offsetting 100 degrees from GWB's coordinate reproduces the same local
     * heading a real OSRM polyline point would have on the tolled approach, without needing the
     * exact upstream road geometry.
     */
    static Wp offset(double lat, double lng, double bearingDeg, double distanceMeters) {
        double bearingRad = Math.toRadians(bearingDeg);
        double deltaLat = (distanceMeters * Math.cos(bearingRad)) / 111_320.0;
        double deltaLng = (distanceMeters * Math.sin(bearingRad)) / (111_320.0 * Math.cos(Math.toRadians(lat)));
        return new Wp(lat + deltaLat, lng + deltaLng);
    }

    /**
     * The three waypoints a vehicle traveling at travelBearingDeg through a crossing produces,
     * a point legMeters before the crossing, the exact crossing coordinate, and a point
     * legMeters past it, all along that exact bearing. Passing the crossing's own
     * travel_bearing_deg reproduces the tolled direction, passing its reverse, travelBearingDeg
     * plus 180, reproduces the untolled direction for a FORWARD crossing.
     */
    static Wp[] approach(double lat, double lng, double travelBearingDeg, double legMeters) {
        Wp before = offset(lat, lng, (travelBearingDeg + 180) % 360, legMeters);
        Wp exact = new Wp(lat, lng);
        Wp after = offset(lat, lng, travelBearingDeg, legMeters);
        return new Wp[] {before, exact, after};
    }

    /**
     * Builds a route at a constant average driving speed. Used wherever a test only cares which
     * crossings are detected and roughly when, not an exact arrival instant.
     */
    static Route atConstantSpeed(double metersPerSecond, Wp... waypoints) {
        List<RoutePoint> points = new ArrayList<>(waypoints.length);
        double cumulativeSeconds = 0;
        points.add(new RoutePoint(waypoints[0].lat(), waypoints[0].lng(), 0));

        double totalDistance = 0;
        for (int i = 1; i < waypoints.length; i++) {
            Wp previous = waypoints[i - 1];
            Wp current = waypoints[i];
            double segmentDistance = GeoMath.distanceMeters(previous.lat(), previous.lng(), current.lat(), current.lng());
            totalDistance += segmentDistance;
            cumulativeSeconds += segmentDistance / metersPerSecond;
            points.add(new RoutePoint(current.lat(), current.lng(), cumulativeSeconds));
        }

        return new Route(points, totalDistance, cumulativeSeconds);
    }

    /**
     * Builds a route from explicit cumulative duration timings, one per waypoint, so a test can
     * pin an exact arrival instant at a specific crossing, the peak versus off peak boundary
     * test needs this precision and constant speed simulation over a multi state trip cannot
     * give it.
     */
    static Route withExplicitTimings(TimedWp... waypoints) {
        List<RoutePoint> points = new ArrayList<>(waypoints.length);
        double totalDistance = 0;
        for (int i = 0; i < waypoints.length; i++) {
            TimedWp wp = waypoints[i];
            points.add(new RoutePoint(wp.lat(), wp.lng(), wp.cumulativeSeconds()));
            if (i > 0) {
                totalDistance += GeoMath.distanceMeters(
                        waypoints[i - 1].lat(), waypoints[i - 1].lng(), wp.lat(), wp.lng());
            }
        }
        double totalDuration = waypoints.length == 0 ? 0 : waypoints[waypoints.length - 1].cumulativeSeconds();
        return new Route(points, totalDistance, totalDuration);
    }

    record TimedWp(double lat, double lng, double cumulativeSeconds) {
    }

    /** Flattens a mix of single waypoints and approach triples into one ordered waypoint array. */
    static Wp[] chain(Wp[]... parts) {
        List<Wp> all = new ArrayList<>();
        for (Wp[] part : parts) {
            all.addAll(List.of(part));
        }
        return all.toArray(new Wp[0]);
    }

    static Wp[] one(Wp wp) {
        return new Wp[] {wp};
    }
}
