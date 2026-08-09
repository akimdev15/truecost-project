package com.truecost.toll;

import com.truecost.domain.Money;
import java.time.Instant;

/**
 * The priced congestion charge for a route that enters the zone. netAmount is scheduleAmount
 * minus creditAmount floored at zero, and creditCrossingCode is the zone entry tunnel that earned
 * the credit, or null when none applied.
 */
public record CongestionCharge(
        Instant chargeInstant,
        String period,
        Money scheduleAmount,
        Money creditAmount,
        Money netAmount,
        String creditCrossingCode,
        boolean oncePerDay) {
}
