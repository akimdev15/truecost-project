package com.truecost.persist;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CongestionScheduleRepository {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final JdbcClient jdbcClient;

    public CongestionScheduleRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public int count() {
        return jdbcClient.sql("select count(*) from congestion_schedule").query(Integer.class).single();
    }

    /**
     * Resolves the congestion_schedule row for a toll class, payment type, and trip instant,
     * following the same day mask plus local time window lookup contract as
     * TollRateRepository.findRateCents, so peak versus overnight and effective date supersession
     * work identically for the two reference tables.
     */
    public Optional<ScheduleRow> findSchedule(String tollClass, String paymentType, Instant tripInstant) {
        ZonedDateTime localTrip = tripInstant.atZone(NEW_YORK);
        LocalDate tripDate = localTrip.toLocalDate();
        LocalTime tripTime = localTrip.toLocalTime();
        int isoDayOfWeek = localTrip.getDayOfWeek().getValue();

        return jdbcClient.sql("""
                        select period::text as period, amount_cents as amountCents, once_per_day as oncePerDay
                        from congestion_schedule
                        where toll_class::text = :tollClass
                          and payment_type::text = :paymentType
                          and effective_date <= :tripDate
                          and (day_of_week_mask & (1 << (:isoDayOfWeek - 1))) <> 0
                          and :tripTime >= window_start
                          and :tripTime < window_end
                        order by effective_date desc
                        limit 1
                        """)
                .param("tollClass", tollClass)
                .param("paymentType", paymentType)
                .param("tripDate", tripDate)
                .param("isoDayOfWeek", isoDayOfWeek)
                .param("tripTime", tripTime)
                .query(ScheduleRow.class)
                .optional();
    }

    public record ScheduleRow(String period, long amountCents, boolean oncePerDay) {
    }

    /** windowStart and windowEnd are HH:mm text, cast to time in SQL. See TollRateRepository.insert. */
    public int insert(String period, String tollClass, String paymentType, int dayOfWeekMask,
            String windowStart, String windowEnd, long amountCents, boolean oncePerDay, LocalDate effectiveDate) {
        return jdbcClient.sql("""
                        insert into congestion_schedule
                            (period, toll_class, payment_type, day_of_week_mask, window_start, window_end,
                             amount_cents, once_per_day, effective_date)
                        values
                            (:period::congestion_period, :tollClass::toll_class, :paymentType::payment_type,
                             :dayOfWeekMask, :windowStart::time, :windowEnd::time, :amountCents, :oncePerDay, :effectiveDate)
                        """)
                .param("period", period)
                .param("tollClass", tollClass)
                .param("paymentType", paymentType)
                .param("dayOfWeekMask", dayOfWeekMask)
                .param("windowStart", windowStart)
                .param("windowEnd", windowEnd)
                .param("amountCents", amountCents)
                .param("oncePerDay", oncePerDay)
                .param("effectiveDate", effectiveDate)
                .update();
    }
}
