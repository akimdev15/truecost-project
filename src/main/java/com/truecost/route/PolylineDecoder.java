package com.truecost.route;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes the Google encoded polyline algorithm format at precision five, five decimal digits per
 * degree, the OSRM HTTP API's default geometry encoding that RouteClient requests explicitly
 * rather than OSRM's alternative precision six or GeoJSON output.
 */
final class PolylineDecoder {

    private PolylineDecoder() {
    }

    static List<double[]> decode(String encoded) {
        List<double[]> points = new ArrayList<>();
        int index = 0;
        int length = encoded.length();
        long lat = 0;
        long lng = 0;

        while (index < length) {
            lat += decodeSignedValue(encoded, index);
            index = advancePastValue(encoded, index);

            lng += decodeSignedValue(encoded, index);
            index = advancePastValue(encoded, index);

            points.add(new double[] {lat / 1e5, lng / 1e5});
        }
        return points;
    }

    private static long decodeSignedValue(String encoded, int startIndex) {
        long result = decodeVarint(encoded, startIndex);
        return (result & 1) != 0 ? ~(result >> 1) : (result >> 1);
    }

    private static long decodeVarint(String encoded, int startIndex) {
        long result = 0;
        int shift = 0;
        int index = startIndex;
        int b;
        do {
            b = encoded.charAt(index++) - 63;
            result |= (long) (b & 0x1f) << shift;
            shift += 5;
        } while (b >= 0x20);
        return result;
    }

    private static int advancePastValue(String encoded, int startIndex) {
        int index = startIndex;
        int b;
        do {
            b = encoded.charAt(index++) - 63;
        } while (b >= 0x20);
        return index;
    }
}
