package com.truecost.provider.fuel;

/**
 * isFallback is always false here. A failed EIA fetch throws rather than producing a value to
 * cache, so this field exists only to keep the cached JSON shape consistent.
 */
public record FuelPriceValue(double pricePerGallon, boolean isFallback) {
}
