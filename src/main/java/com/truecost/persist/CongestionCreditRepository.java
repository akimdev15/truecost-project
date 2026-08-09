package com.truecost.persist;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CongestionCreditRepository {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final JdbcClient jdbcClient;

    public CongestionCreditRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public int count() {
        return jdbcClient.sql("select count(*) from congestion_credit").query(Integer.class).single();
    }

    /**
     * The crossing credit for one of the four zone entry tunnels, keyed by crossing code so the
     * congestion pricer can look it up directly from a detected crossing without a separate id
     * resolution step. Only PEAK applies_period rows exist, so a credit found here is always the
     * peak entry credit, floored against the day's schedule amount by the caller.
     */
    public Optional<Long> findCreditCents(String crossingCode, String tollClass, String paymentType, Instant tripInstant) {
        LocalDate tripDate = tripInstant.atZone(NEW_YORK).toLocalDate();

        return jdbcClient.sql("""
                        select cc.credit_cents
                        from congestion_credit cc
                        join toll_crossing tc on tc.id = cc.crossing_id
                        where tc.code = :crossingCode
                          and cc.toll_class::text = :tollClass
                          and cc.payment_type::text = :paymentType
                          and cc.effective_date <= :tripDate
                        order by cc.effective_date desc
                        limit 1
                        """)
                .param("crossingCode", crossingCode)
                .param("tollClass", tollClass)
                .param("paymentType", paymentType)
                .param("tripDate", tripDate)
                .query(Long.class)
                .optional();
    }

    public int insert(long crossingId, String tollClass, String paymentType, String appliesPeriod,
            long creditCents, LocalDate effectiveDate) {
        return jdbcClient.sql("""
                        insert into congestion_credit
                            (crossing_id, toll_class, payment_type, applies_period, credit_cents, effective_date)
                        values
                            (:crossingId, :tollClass::toll_class, :paymentType::payment_type,
                             :appliesPeriod::congestion_period, :creditCents, :effectiveDate)
                        """)
                .param("crossingId", crossingId)
                .param("tollClass", tollClass)
                .param("paymentType", paymentType)
                .param("appliesPeriod", appliesPeriod)
                .param("creditCents", creditCents)
                .param("effectiveDate", effectiveDate)
                .update();
    }
}
