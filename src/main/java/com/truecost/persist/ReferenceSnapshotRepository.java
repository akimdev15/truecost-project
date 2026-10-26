package com.truecost.persist;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The date the seeded reference rates were verified against their sources, loaded from
 * data/seeds/snapshot.csv. It is a single row table so the date travels with the data it describes.
 */
@Repository
public class ReferenceSnapshotRepository {

    private final JdbcClient jdbcClient;

    public ReferenceSnapshotRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<LocalDate> findSnapshotDate() {
        return jdbcClient.sql("select snapshot_date from reference_snapshot")
                .query(LocalDate.class)
                .optional();
    }

    public int insert(LocalDate snapshotDate) {
        return jdbcClient.sql("insert into reference_snapshot (snapshot_date) values (:snapshotDate)")
                .param("snapshotDate", snapshotDate)
                .update();
    }
}
