package com.truecost.api.dto;

/**
 * Where an option's rental, fuel, and hotel figures came from, so a placeholder or fallback price
 * is never shown to the user as if it were a live one. hotelSource is null when hotel is
 * unavailable.
 */
public record OptionProvenance(String rentalSource, boolean fuelPriceIsFallback, String hotelSource) {
}
