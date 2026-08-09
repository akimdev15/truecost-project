package com.truecost.provider.rental;

import java.time.LocalDate;

/**
 * A rental quote lookup for one pickup location, date range, and car class. pickupLocationCode
 * is an airport or city code such as JFK or EWR, matching the codes rental providers key their
 * inventory by.
 */
public record RentalQuoteRequest(
        String pickupLocationCode,
        LocalDate pickupDate,
        LocalDate returnDate,
        String carClassCode) {
}
