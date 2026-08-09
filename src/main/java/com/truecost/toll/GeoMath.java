package com.truecost.toll;

/**
 * Great circle distance and bearing helpers shared by crossing detection and congestion zone
 * detection. Uses plain Java geometry rather than PostGIS since the NYC weekend trip space is
 * small enough that spherical approximation error stays well under the fifty meter detection
 * threshold.
 */
final class GeoMath {

    private static final double EARTH_RADIUS_METERS = 6371000.0;

    private GeoMath() {
    }

    /** Great circle distance between two points in meters, using the haversine formula. */
    static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaPhi = Math.toRadians(lat2 - lat1);
        double deltaLambda = Math.toRadians(lng2 - lng1);

        double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_METERS * c;
    }

    /** Initial compass bearing in degrees, 0 to 360 exclusive of 360, from point one to point two. */
    static double bearingDegrees(double lat1, double lng1, double lat2, double lng2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaLambda = Math.toRadians(lng2 - lng1);

        double y = Math.sin(deltaLambda) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(deltaLambda);
        double theta = Math.atan2(y, x);
        return (Math.toDegrees(theta) + 360.0) % 360.0;
    }

    /** Smallest angle in degrees, 0 to 180, between two compass bearings. */
    static double circularDifferenceDegrees(double bearingOne, double bearingTwo) {
        double diff = Math.abs(bearingOne - bearingTwo) % 360.0;
        return diff > 180.0 ? 360.0 - diff : diff;
    }

    static double reverseBearing(double bearingDeg) {
        return (bearingDeg + 180.0) % 360.0;
    }
}
