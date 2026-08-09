package com.truecost.persist;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TollCrossingRepository {

    private final JdbcClient jdbcClient;

    public TollCrossingRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public int count() {
        return jdbcClient.sql("select count(*) from toll_crossing").query(Integer.class).single();
    }

    public Optional<Long> findIdByCode(String code) {
        return jdbcClient.sql("select id from toll_crossing where code = :code")
                .param("code", code)
                .query(Long.class)
                .optional();
    }

    /**
     * All seeded crossings with the geometry fields the Phase 2 crossing detector matches a
     * route polyline against. Small, fixed reference table, roughly twenty rows, read in full
     * once per detection pass rather than queried per candidate point.
     */
    public List<CrossingRow> findAll() {
        return jdbcClient.sql("""
                        select code, name, agency::text as agency, crossing_type::text as crossingType,
                               latitude, longitude, travel_bearing_deg as travelBearingDeg,
                               tolled_directions::text as tolledDirections
                        from toll_crossing
                        order by code
                        """)
                .query(CrossingRow.class)
                .list();
    }

    public record CrossingRow(
            String code,
            String name,
            String agency,
            String crossingType,
            double latitude,
            double longitude,
            int travelBearingDeg,
            String tolledDirections) {
    }

    public int insert(String code, String name, String agency, String crossingType,
            double latitude, double longitude, int travelBearingDeg, String tolledDirections) {
        return jdbcClient.sql("""
                        insert into toll_crossing
                            (code, name, agency, crossing_type, latitude, longitude, travel_bearing_deg, tolled_directions)
                        values
                            (:code, :name, :agency::toll_agency, :crossingType::crossing_type,
                             :latitude, :longitude, :travelBearingDeg, :tolledDirections::tolled_direction)
                        """)
                .param("code", code)
                .param("name", name)
                .param("agency", agency)
                .param("crossingType", crossingType)
                .param("latitude", latitude)
                .param("longitude", longitude)
                .param("travelBearingDeg", travelBearingDeg)
                .param("tolledDirections", tolledDirections)
                .update();
    }
}
