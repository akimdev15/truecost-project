package com.truecost.api.dto;

/**
 * One priced rental option. trueTotalCents is rentalCents plus strategy.totalCents (which itself
 * sums tollProgramFeeCents, tollTotalCents, and congestionCents) plus fuelCents plus hotelCents,
 * so nothing is double counted.
 */
public record TripOption(
        String companyCode,
        String companyName,
        String carClassCode,
        long rentalCents,
        long tollProgramFeeCents,
        long tollTotalCents,
        long congestionCents,
        long fuelCents,
        long hotelCents,
        long trueTotalCents,
        ChosenStrategy strategy,
        OptionProvenance provenance,
        boolean partial) {
}
