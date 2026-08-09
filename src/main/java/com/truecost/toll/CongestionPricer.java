package com.truecost.toll;

import com.truecost.domain.Money;
import com.truecost.persist.CongestionCreditRepository;
import com.truecost.persist.CongestionScheduleRepository;
import com.truecost.route.Route;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Prices the Congestion Relief Zone charge for a route, if any. Takes the crossings TollTimeline
 * already detected rather than redetecting them, since the credit rule only cares whether the
 * trip crossed one of the four zone entry tunnels, Lincoln, Holland, Queens Midtown, or Hugh
 * Carey, a fact CrossingDetector already established.
 */
@Component
public class CongestionPricer {

    private static final Logger log = LoggerFactory.getLogger(CongestionPricer.class);

    private final CongestionZoneDetector zoneDetector;
    private final CongestionScheduleRepository scheduleRepository;
    private final CongestionCreditRepository creditRepository;

    public CongestionPricer(
            CongestionZoneDetector zoneDetector,
            CongestionScheduleRepository scheduleRepository,
            CongestionCreditRepository creditRepository) {
        this.zoneDetector = zoneDetector;
        this.scheduleRepository = scheduleRepository;
        this.creditRepository = creditRepository;
    }

    public Optional<CongestionCharge> price(
            Route route,
            List<DetectedCrossing> detectedCrossings,
            Instant departureInstant,
            String tollClass,
            String paymentType) {
        Optional<ZoneEntry> entry = zoneDetector.detectEntry(route);
        if (entry.isEmpty()) {
            return Optional.empty();
        }

        Instant chargeInstant = departureInstant.plusMillis(Math.round(entry.get().cumulativeDurationSeconds() * 1000.0));
        Optional<CongestionScheduleRepository.ScheduleRow> schedule =
                scheduleRepository.findSchedule(tollClass, paymentType, chargeInstant);
        if (schedule.isEmpty()) {
            log.warn("zone entry detected but no congestion_schedule row for tollClass={} paymentType={} instant={}",
                    tollClass, paymentType, chargeInstant);
            return Optional.empty();
        }

        CongestionScheduleRepository.ScheduleRow row = schedule.get();
        Money scheduleAmount = Money.ofCents(row.amountCents());
        Money credit = Money.ZERO;
        String creditCrossingCode = null;

        if ("PEAK".equals(row.period())) {
            for (DetectedCrossing crossing : detectedCrossings) {
                Optional<Long> creditCents =
                        creditRepository.findCreditCents(crossing.crossingCode(), tollClass, paymentType, chargeInstant);
                if (creditCents.isPresent()) {
                    credit = Money.ofCents(creditCents.get());
                    creditCrossingCode = crossing.crossingCode();
                    break;
                }
            }
        }

        long netCents = Math.max(scheduleAmount.cents() - credit.cents(), 0L);
        Money net = Money.ofCents(netCents);

        log.info("congestion charge computed, period={} scheduleCents={} creditCents={} netCents={} creditCrossing={}",
                row.period(), scheduleAmount.cents(), credit.cents(), net.cents(), creditCrossingCode);

        return Optional.of(new CongestionCharge(
                chargeInstant, row.period(), scheduleAmount, credit, net, creditCrossingCode, row.oncePerDay()));
    }
}
