package com.truecost.coverage;

/** One seeded rectangle of toll coverage. Edges are inclusive, so a point exactly on a boundary is covered. */
public record CoverageRegion(String code, String name, double minLat, double minLng, double maxLat, double maxLng) {

    boolean contains(double lat, double lng) {
        return lat >= minLat && lat <= maxLat && lng >= minLng && lng <= maxLng;
    }
}
