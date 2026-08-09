package com.truecost.toll;

import com.truecost.persist.TollRateRepository;
import com.truecost.route.Route;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns the crossings CrossingDetector finds along a route into a priced timeline. Both the
 * E-ZPass and maximum cash or Tolls by Mail rate are resolved for every crossing so the toll
 * strategy engine can compare rate bases without a second trip to the rate table, and pricing at
 * the estimated arrival instant rather than departure is what makes peak versus off peak boundary
 * cases price correctly.
 */
@Component
public class TollTimeline {

    private static final Logger log = LoggerFactory.getLogger(TollTimeline.class);
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final CrossingDetector crossingDetector;
    private final TollRateRepository rateRepository;

    public TollTimeline(CrossingDetector crossingDetector, TollRateRepository rateRepository) {
        this.crossingDetector = crossingDetector;
        this.rateRepository = rateRepository;
    }

    public List<PricedCrossing> price(Route route, Instant departureInstant, String vehicleClassCode) {
        List<DetectedCrossing> detected = crossingDetector.detect(route);
        List<PricedCrossing> priced = new ArrayList<>(detected.size());

        for (DetectedCrossing crossing : detected) {
            Instant estimatedArrival = departureInstant.plusMillis(Math.round(crossing.cumulativeDurationSeconds() * 1000.0));
            Optional<Long> ezpassCents = rateRepository.findRateCents(
                    crossing.crossingCode(), vehicleClassCode, "EZPASS", estimatedArrival);
            Optional<Long> cashOrMailCents = rateRepository.findMaxCashOrMailCents(
                    crossing.crossingCode(), vehicleClassCode, estimatedArrival);

            if (ezpassCents.isEmpty() || cashOrMailCents.isEmpty()) {
                log.warn("incomplete toll_rate coverage for crossing={} vehicleClass={} arrival={}, "
                                + "ezpassPresent={} cashOrMailPresent={}, excluded from the priced timeline",
                        crossing.crossingCode(), vehicleClassCode, estimatedArrival,
                        ezpassCents.isPresent(), cashOrMailCents.isPresent());
                continue;
            }

            LocalDate tollDate = estimatedArrival.atZone(NEW_YORK).toLocalDate();
            priced.add(new PricedCrossing(
                    crossing.crossingCode(), crossing.crossingName(), tollDate, ezpassCents.get(), cashOrMailCents.get()));
        }

        return priced;
    }
}
