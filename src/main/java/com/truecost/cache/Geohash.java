package com.truecost.cache;

/**
 * Base32 geohash encoding truncated to six characters, precise enough to keep the 50 meter
 * crossing detection network's approach matching correct while still coalescing near-identical
 * searches. decodeCellCenter returns the cell center rather than the original point, since the
 * route loader queries OSRM at that center so every cached route stays a deterministic function of
 * its key.
 */
public final class Geohash {

    private static final String BASE32_ALPHABET = "0123456789bcdefghjkmnpqrstuvwxyz";
    private static final int PRECISION = 6;

    private Geohash() {
    }

    public static String encode(double lat, double lng) {
        double[] latRange = {-90.0, 90.0};
        double[] lngRange = {-180.0, 180.0};
        StringBuilder geohash = new StringBuilder(PRECISION);
        boolean evenBit = true;
        int bit = 0;
        int charValue = 0;

        while (geohash.length() < PRECISION) {
            if (evenBit) {
                double mid = (lngRange[0] + lngRange[1]) / 2;
                if (lng >= mid) {
                    charValue |= (16 >> bit);
                    lngRange[0] = mid;
                } else {
                    lngRange[1] = mid;
                }
            } else {
                double mid = (latRange[0] + latRange[1]) / 2;
                if (lat >= mid) {
                    charValue |= (16 >> bit);
                    latRange[0] = mid;
                } else {
                    latRange[1] = mid;
                }
            }
            evenBit = !evenBit;
            if (bit < 4) {
                bit++;
            } else {
                geohash.append(BASE32_ALPHABET.charAt(charValue));
                bit = 0;
                charValue = 0;
            }
        }
        return geohash.toString();
    }

    public static GeoPoint decodeCellCenter(String geohash) {
        double[] latRange = {-90.0, 90.0};
        double[] lngRange = {-180.0, 180.0};
        boolean evenBit = true;

        for (int i = 0; i < geohash.length(); i++) {
            int charValue = BASE32_ALPHABET.indexOf(geohash.charAt(i));
            if (charValue < 0) {
                throw new IllegalArgumentException("not a base32 geohash character: " + geohash.charAt(i));
            }
            for (int mask : new int[] {16, 8, 4, 2, 1}) {
                if (evenBit) {
                    double mid = (lngRange[0] + lngRange[1]) / 2;
                    if ((charValue & mask) != 0) {
                        lngRange[0] = mid;
                    } else {
                        lngRange[1] = mid;
                    }
                } else {
                    double mid = (latRange[0] + latRange[1]) / 2;
                    if ((charValue & mask) != 0) {
                        latRange[0] = mid;
                    } else {
                        latRange[1] = mid;
                    }
                }
                evenBit = !evenBit;
            }
        }
        return new GeoPoint((latRange[0] + latRange[1]) / 2, (lngRange[0] + lngRange[1]) / 2);
    }
}
