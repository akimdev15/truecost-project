package com.truecost.aggregate;

import com.truecost.cache.TollsCacheValue;
import com.truecost.domain.Money;
import com.truecost.route.Route;
import com.truecost.toll.CongestionCharge;
import com.truecost.toll.CongestionPricer;
import com.truecost.toll.CrossingDetector;
import com.truecost.toll.DetectedCrossing;
import com.truecost.toll.PricedCrossing;
import com.truecost.toll.TollTimeline;
import com.truecost.toll.strategy.CongestionDay;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Callers pass one representative vehicle_class.code per toll class because TollTimeline.price and
 * TollRateRepository resolve toll_class internally from a concrete rental code, not from a
 * toll_class directly. This pricer also re-runs CrossingDetector.detect on its own, duplicating
 * TollTimeline.price's internal call, since that method does not expose the crossings it used and
 * detection is cheap enough that the duplication is harmless.
 */
@Component
public class LegPricer {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final CrossingDetector crossingDetector;
    private final TollTimeline tollTimeline;
    private final CongestionPricer congestionPricer;

    public LegPricer(CrossingDetector crossingDetector, TollTimeline tollTimeline, CongestionPricer congestionPricer) {
        this.crossingDetector = crossingDetector;
        this.tollTimeline = tollTimeline;
        this.congestionPricer = congestionPricer;
    }

    public TollsCacheValue priceLeg(Route route, Instant instant, String tollClass, String representativeVehicleClassCode) {
        List<DetectedCrossing> detected = crossingDetector.detect(route);
        List<PricedCrossing> pricedCrossings = tollTimeline.price(route, instant, representativeVehicleClassCode);

        Optional<CongestionCharge> ezpassCharge =
                congestionPricer.price(route, detected, instant, tollClass, "EZPASS");
        Optional<CongestionCharge> mailCharge =
                congestionPricer.price(route, detected, instant, tollClass, "TOLLS_BY_MAIL");

        List<CongestionDay> congestionDays = foldCongestionDay(ezpassCharge, mailCharge);
        return new TollsCacheValue(pricedCrossings, congestionDays);
    }

    /**
     * Folds the EZPASS and TOLLS_BY_MAIL charges for the same zone entry into at most one
     * CongestionDay, preferring the EZPASS charge for peak and creditTunnel since it is present
     * whenever any charge is, and zero-filling whichever side's net amount has no schedule row.
     */
    private List<CongestionDay> foldCongestionDay(Optional<CongestionCharge> ezpassCharge,
            Optional<CongestionCharge> mailCharge) {
        if (ezpassCharge.isEmpty() && mailCharge.isEmpty()) {
            return List.of();
        }

        CongestionCharge representative = ezpassCharge.orElseGet(mailCharge::get);
        long netEzpassCents = ezpassCharge.map(charge -> charge.netAmount().cents()).orElse(0L);
        long netMailCents = mailCharge.map(charge -> charge.netAmount().cents()).orElse(Money.ZERO.cents());
        LocalDate date = representative.chargeInstant().atZone(NEW_YORK).toLocalDate();
        boolean peak = "PEAK".equals(representative.period());

        return List.of(new CongestionDay(date, peak, netEzpassCents, netMailCents, representative.creditCrossingCode()));
    }
}
