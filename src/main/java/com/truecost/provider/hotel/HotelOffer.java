package com.truecost.provider.hotel;

import com.truecost.domain.Money;

/**
 * One priced hotel option for a stay window. source is a short provenance tag, for example
 * SYNTHETIC or AMADEUS.
 */
public record HotelOffer(String hotelName, Money totalCost, int nights, String source) {
}
