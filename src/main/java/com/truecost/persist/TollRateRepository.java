package com.truecost.persist;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Looks up the toll_rate row for a crossing, rental vehicle class, payment type, and trip
 * instant. The vehicle class to toll_class resolution happens as a join inside the query rather
 * than a separate round trip, since toll_rate is keyed by the agency toll_class, not the rental
 * marketing class.
 */
@Repository
public class TollRateRepository {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final JdbcClient jdbcClient;

    public TollRateRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public int count() {
        return jdbcClient.sql("select count(*) from toll_rate").query(Integer.class).single();
    }

    public Optional<Long> findRateCents(String crossingCode, String vehicleClassCode, String paymentType, Instant tripInstant) {
        ZonedDateTime localTrip = tripInstant.atZone(NEW_YORK);
        LocalDate tripDate = localTrip.toLocalDate();
        LocalTime tripTime = localTrip.toLocalTime();
        int isoDayOfWeek = localTrip.getDayOfWeek().getValue();

        return jdbcClient.sql("""
                        select tr.amount_cents
                        from toll_rate tr
                        join toll_crossing tc on tc.id = tr.crossing_id
                        join vehicle_class vc on vc.toll_class = tr.toll_class
                        where tc.code = :crossingCode
                          and vc.code = :vehicleClassCode
                          and tr.payment_type::text = :paymentType
                          and tr.effective_date <= :tripDate
                          and (tr.day_of_week_mask & (1 << (:isoDayOfWeek - 1))) <> 0
                          and :tripTime >= tr.window_start
                          and :tripTime < tr.window_end
                        order by tr.effective_date desc
                        limit 1
                        """)
                .param("crossingCode", crossingCode)
                .param("vehicleClassCode", vehicleClassCode)
                .param("paymentType", paymentType)
                .param("tripDate", tripDate)
                .param("isoDayOfWeek", isoDayOfWeek)
                .param("tripTime", tripTime)
                .query(Long.class)
                .optional();
    }

    /**
     * The higher of the CASH and TOLLS_BY_MAIL rows for a crossing, matching the toll strategy
     * engine's MAX_CASH_RATE basis. In this seed the two payment types never coexist for one
     * crossing, NJTA publishes CASH and every other agency publishes TOLLS_BY_MAIL, but taking the
     * maximum of whichever rows exist stays correct if that changes later.
     */
    public Optional<Long> findMaxCashOrMailCents(String crossingCode, String vehicleClassCode, Instant tripInstant) {
        Optional<Long> cash = findRateCents(crossingCode, vehicleClassCode, "CASH", tripInstant);
        Optional<Long> mail = findRateCents(crossingCode, vehicleClassCode, "TOLLS_BY_MAIL", tripInstant);
        if (cash.isEmpty()) {
            return mail;
        }
        if (mail.isEmpty()) {
            return cash;
        }
        return Optional.of(Math.max(cash.get(), mail.get()));
    }

    /**
     * windowStart and windowEnd are HH:mm text such as "06:00" or "24:00", cast to time in SQL
     * rather than parsed into java.time.LocalTime here, since LocalTime cannot represent the
     * end-of-day sentinel 24:00 that a full-day window uses, while Postgres time accepts it.
     */
    public int insert(long crossingId, String paymentType, String tollClass, int dayOfWeekMask,
            String windowStart, String windowEnd, long amountCents, LocalDate effectiveDate) {
        return jdbcClient.sql("""
                        insert into toll_rate
                            (crossing_id, payment_type, toll_class, day_of_week_mask, window_start, window_end, amount_cents, effective_date)
                        values
                            (:crossingId, :paymentType::payment_type, :tollClass::toll_class, :dayOfWeekMask,
                             :windowStart::time, :windowEnd::time, :amountCents, :effectiveDate)
                        """)
                .param("crossingId", crossingId)
                .param("paymentType", paymentType)
                .param("tollClass", tollClass)
                .param("dayOfWeekMask", dayOfWeekMask)
                .param("windowStart", windowStart)
                .param("windowEnd", windowEnd)
                .param("amountCents", amountCents)
                .param("effectiveDate", effectiveDate)
                .update();
    }
}
