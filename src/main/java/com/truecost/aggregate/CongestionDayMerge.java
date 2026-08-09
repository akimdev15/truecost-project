package com.truecost.aggregate;

import com.truecost.toll.strategy.CongestionDay;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges the outbound and return legs' congestion days, keeping only the return leg's
 * CongestionDay when both legs charge the same calendar date. Because TripPlanRequest validation
 * requires returnAt strictly after departureAt, the return leg's chargeInstant is always the later
 * one on any shared date, so overwriting by date in insertion order is a correct tie break without
 * CongestionDay needing to carry chargeInstant.
 */
public final class CongestionDayMerge {

    private CongestionDayMerge() {
    }

    public static List<CongestionDay> merge(List<CongestionDay> outboundDays, List<CongestionDay> returnDays) {
        Map<LocalDate, CongestionDay> byDate = new LinkedHashMap<>();
        for (CongestionDay day : outboundDays) {
            byDate.put(day.date(), day);
        }
        for (CongestionDay day : returnDays) {
            byDate.put(day.date(), day);
        }
        return List.copyOf(byDate.values());
    }
}
