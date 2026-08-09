package com.truecost.api.dto;

/**
 * A user-supplied rental price for one company, bypassing provider fetch for that company.
 * totalCents is the full rental total for the whole trip, matching RentalQuote.totalCost, not a
 * daily rate, so no rentalDays multiplication happens after the request boundary.
 */
public record RentalRateOverride(
        String companyCode,
        String companyName,
        long totalCents) {
}
