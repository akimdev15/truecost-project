package com.truecost.cache;

import com.truecost.toll.PricedCrossing;
import com.truecost.toll.strategy.CongestionDay;
import java.util.List;

/**
 * The tolls:v1 cache value for one leg, exactly the two lists TollStrategyEngine consumes,
 * produced by one CrossingDetector.detect, one TollTimeline.price, and two CongestionPricer.price
 * calls. Combining both legs happens at read time with no further I/O once each leg is cached.
 */
public record TollsCacheValue(List<PricedCrossing> pricedCrossings, List<CongestionDay> congestionDays) {
}
