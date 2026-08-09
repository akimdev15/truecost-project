package com.truecost.cache;

/**
 * The outcome of a TwoTierCache.get call, value paired with the freshness it was served at.
 * Downstream callers read this pair directly rather than re-deriving freshness themselves.
 */
public record CacheResult<T>(T value, DataFreshness freshness) {
}
