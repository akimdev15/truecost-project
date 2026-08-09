package com.truecost.toll.strategy;

import java.time.LocalDate;

/**
 * One calendar date on which the vehicle enters the MTA Congestion Relief Zone. netEzpassCents
 * and netMailCents already have the tunnel credit subtracted and clamped at zero, while peak and
 * creditTunnel feed only the human readable explanation, never a cost formula.
 */
public record CongestionDay(
        LocalDate date,
        boolean peak,
        long netEzpassCents,
        long netMailCents,
        String creditTunnel) {
}
