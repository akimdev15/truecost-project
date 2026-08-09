package com.truecost.persist;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class VehicleClassRepository {

    private final JdbcClient jdbcClient;

    public VehicleClassRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public int count() {
        return jdbcClient.sql("select count(*) from vehicle_class").query(Integer.class).single();
    }

    public String findTollClass(String code) {
        return jdbcClient.sql("select toll_class::text from vehicle_class where code = :code")
                .param("code", code)
                .query(String.class)
                .single();
    }

    /** Every rental car class code, used to fan out one rental fetch per class when the request leaves carClass unset. */
    public List<String> findAllCodes() {
        return jdbcClient.sql("select code from vehicle_class order by code").query(String.class).list();
    }

    /**
     * One representative vehicle_class.code for a toll_class, since TollTimeline.price and
     * TollRateRepository require a concrete rental code rather than a toll_class directly. The
     * tolls loader prices once per leg using this code, and the result applies to every other car
     * class sharing the toll_class since toll_rate is keyed by toll_class, not the rental
     * marketing class.
     */
    public String findRepresentativeCodeForTollClass(String tollClass) {
        return jdbcClient.sql("""
                        select code from vehicle_class where toll_class::text = :tollClass order by code limit 1
                        """)
                .param("tollClass", tollClass)
                .query(String.class)
                .single();
    }

    public double findDefaultMpg(String code) {
        return jdbcClient.sql("select default_mpg from vehicle_class where code = :code")
                .param("code", code)
                .query(Double.class)
                .single();
    }

    public int insert(String code, String name, String tollClass, double defaultMpg) {
        return jdbcClient.sql("""
                        insert into vehicle_class (code, name, toll_class, default_mpg)
                        values (:code, :name, :tollClass::toll_class, :defaultMpg)
                        """)
                .param("code", code)
                .param("name", name)
                .param("tollClass", tollClass)
                .param("defaultMpg", defaultMpg)
                .update();
    }
}
