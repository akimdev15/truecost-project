package com.truecost.persist;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CoverageRegionRepository {

    private final JdbcClient jdbcClient;

    public CoverageRegionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<CoverageRegionRow> findAll() {
        return jdbcClient.sql("""
                        select code, name, min_lat as minLat, min_lng as minLng,
                               max_lat as maxLat, max_lng as maxLng
                        from coverage_region
                        order by code
                        """)
                .query(CoverageRegionRow.class)
                .list();
    }

    public int insert(String code, String name, double minLat, double minLng, double maxLat, double maxLng) {
        return jdbcClient.sql("""
                        insert into coverage_region (code, name, min_lat, min_lng, max_lat, max_lng)
                        values (:code, :name, :minLat, :minLng, :maxLat, :maxLng)
                        """)
                .param("code", code)
                .param("name", name)
                .param("minLat", minLat)
                .param("minLng", minLng)
                .param("maxLat", maxLat)
                .param("maxLng", maxLng)
                .update();
    }

    public record CoverageRegionRow(
            String code, String name, double minLat, double minLng, double maxLat, double maxLng) {
    }
}
