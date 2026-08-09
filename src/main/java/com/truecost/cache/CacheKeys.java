package com.truecost.cache;

import java.time.LocalDate;

/**
 * Each key is prefixed by its data class and a v1 schema segment, so a schema change invalidates
 * cleanly by bumping the version segment.
 */
public final class CacheKeys {

    private CacheKeys() {
    }

    public static String route(String legRouteHash) {
        return "route:v1:" + legRouteHash;
    }

    public static String tolls(String legRouteHash, String tollClass, String legBucket) {
        return "tolls:v1:" + legRouteHash + ":" + tollClass + ":" + legBucket;
    }

    public static String rental(String provider, String pickupLocationCode, String carClassCode,
            LocalDate pickupDate, LocalDate returnDate) {
        return "rental:v1:" + provider + ":" + pickupLocationCode + ":" + carClassCode + ":"
                + pickupDate + ":" + returnDate;
    }

    public static String fuel(String eiaRegion) {
        return "fuel:v1:price:" + eiaRegion;
    }

    public static String hotel(String provider, String destinationCode, LocalDate checkIn, LocalDate checkOut) {
        return "hotel:v1:" + provider + ":" + destinationCode + ":" + checkIn + ":" + checkOut;
    }

    /** The data class tag, the first colon delimited segment, used to tag cache metrics by class. */
    public static String classOf(String cacheKey) {
        int firstColon = cacheKey.indexOf(':');
        return firstColon < 0 ? cacheKey : cacheKey.substring(0, firstColon);
    }
}
