package com.truecost.provider.rental;

import com.truecost.domain.Money;

/**
 * One priced rental option from one company for one car class over the requested date range.
 * source is a short provenance tag, for example SYNTHETIC or RAPIDAPI, carried through so a
 * placeholder or fallback quote is never presented to the user as if it were a live price.
 */
public record RentalQuote(
        String companyCode,
        String companyName,
        String carClassCode,
        Money totalCost,
        int rentalDays,
        String source) {
}
