package com.truecost.cache;

import java.time.Duration;
import java.time.Instant;

/**
 * Wraps every cached value because a plain TTL cannot support stale-while-revalidate, the store
 * would evict the entry at the logical TTL with nothing left for the stale window. The physical
 * store TTL is set to twice the logical TTL to leave that window.
 */
public record CacheEnvelope<T>(T value, Instant fetchedAt, Instant expiresAt) {

    public static <T> CacheEnvelope<T> freshlyFetched(T value, Instant fetchedAt, Duration logicalTtl) {
        return new CacheEnvelope<>(value, fetchedAt, fetchedAt.plus(logicalTtl));
    }

    public boolean isFresh(Instant now) {
        return now.isBefore(expiresAt);
    }

    public boolean isWithinStaleWindow(Instant now, Duration logicalTtl) {
        return now.isBefore(expiresAt.plus(logicalTtl));
    }
}
