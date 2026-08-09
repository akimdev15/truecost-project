package com.truecost.toll.strategy;

import com.truecost.toll.PricedCrossing;
import java.util.List;

/**
 * The complete input to the toll strategy engine. There is no separate usage-day count field, the
 * engine derives it itself from the distinct dates in crossings rather than accepting it as a
 * hand supplied scalar that could drift out of sync.
 */
public record StrategyInput(
        List<PricedCrossing> crossings,
        List<CongestionDay> congestionDays,
        int rentalDays,
        boolean ownsPersonalTag,
        List<TollProgram> programs) {
}
