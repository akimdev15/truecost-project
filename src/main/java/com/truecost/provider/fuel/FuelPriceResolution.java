package com.truecost.provider.fuel;

import com.truecost.cache.DataFreshness;

/**
 * freshness is always UNAVAILABLE when isFallback is true, since a fallback value never came from
 * the cache, and FRESH or STALE otherwise.
 */
public record FuelPriceResolution(double pricePerGallon, boolean isFallback, DataFreshness freshness) {
}
