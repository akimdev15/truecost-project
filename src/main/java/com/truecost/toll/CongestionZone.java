package com.truecost.toll;

/**
 * The MTA Congestion Relief Zone boundary, Manhattan south of and including 60th Street. This is
 * a simplified polygon drawn inset from the actual shoreline so that roadways carved out of the
 * real zone, the FDR Drive, Route 9A, and the Battery Park Underpass, naturally fall outside it,
 * accurate enough for fifty meter crossing detection but not pixel accurate at the block level.
 */
final class CongestionZone {

    /**
     * Ring of latitude and longitude vertices tracing the zone boundary clockwise from the
     * northwest corner at 60th Street and the west side. The ray casting algorithm in contains
     * treats the ring as implicitly closed, so the last vertex does not repeat the first.
     */
    private static final double[][] BOUNDARY = {
        {40.7712, -73.9897},
        {40.7605, -73.9945},
        {40.7480, -74.0035},
        {40.7350, -74.0090},
        {40.7195, -74.0125},
        {40.7130, -74.0140},
        {40.7040, -74.0155},
        {40.7010, -74.0145},
        {40.7010, -74.0095},
        {40.7075, -73.9975},
        {40.7180, -73.9805},
        {40.7320, -73.9740},
        {40.7460, -73.9695},
        {40.7560, -73.9615},
        {40.7627, -73.9560},
    };

    private CongestionZone() {
    }

    /** Standard ray casting point in polygon test, odd number of edge crossings means inside. */
    static boolean contains(double lat, double lng) {
        boolean inside = false;
        int vertexCount = BOUNDARY.length;

        for (int i = 0, j = vertexCount - 1; i < vertexCount; j = i++) {
            double latI = BOUNDARY[i][0];
            double lngI = BOUNDARY[i][1];
            double latJ = BOUNDARY[j][0];
            double lngJ = BOUNDARY[j][1];

            boolean straddles = (latI > lat) != (latJ > lat);
            if (straddles) {
                double crossingLng = lngI + (lat - latI) / (latJ - latI) * (lngJ - lngI);
                if (lng < crossingLng) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }
}
