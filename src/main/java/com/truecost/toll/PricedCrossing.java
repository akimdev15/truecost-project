package com.truecost.toll;

import java.time.LocalDate;

/**
 * A detected crossing priced at its estimated arrival date, carrying both the E-ZPass rate and
 * the maximum cash or Tolls by Mail rate so the toll strategy engine can evaluate every strategy
 * without a second trip to the rate table. tollDate is the local New York calendar date of the
 * estimated arrival, the sole source of the usage day count the engine derives.
 */
public record PricedCrossing(
        String crossingCode,
        String crossingName,
        LocalDate tollDate,
        long ezpassCents,
        long cashOrMailCents) {
}
