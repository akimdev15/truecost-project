package com.truecost.provider.fuel;

import com.truecost.domain.Money;

/**
 * mpgUsed always comes from vehicle_class.default_mpg, there is no per make or model lookup yet.
 * priceIsFallback is true whenever the static fallback price was used instead of a live EIA fetch.
 */
public record FuelEstimate(Money cost, double pricePerGallonUsed, double mpgUsed, boolean priceIsFallback) {
}
