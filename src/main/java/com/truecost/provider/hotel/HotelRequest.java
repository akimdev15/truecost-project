package com.truecost.provider.hotel;

import java.time.LocalDate;

/**
 * A hotel search for one destination and stay window. Hotel is a per-trip constant across every
 * rental option, so unlike RentalQuoteRequest this carries no car class or company.
 */
public record HotelRequest(String destinationCode, LocalDate checkIn, LocalDate checkOut) {
}
