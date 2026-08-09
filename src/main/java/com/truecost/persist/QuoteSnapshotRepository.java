package com.truecost.persist;

import com.truecost.events.QuoteSnapshot;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes fetched rental quote snapshots for the thesis accuracy analysis. The insert is idempotent
 * on the snapshot id primary key, so an at-least-once Kafka redelivery of the same QuoteSnapshot
 * event never duplicates a row.
 */
@Repository
public class QuoteSnapshotRepository {

    private final JdbcClient jdbcClient;

    public QuoteSnapshotRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public int insert(QuoteSnapshot snapshot) {
        return jdbcClient.sql("""
                        insert into quote_snapshot
                            (snapshot_id, route_key, origin_lat, origin_lng, dest_lat, dest_lng,
                             company, car_class, provider, base_rate_cents, taxes_and_fees_cents,
                             total_rental_cents, pickup_at, return_at, rental_days, stale, fetched_at)
                        values
                            (:snapshotId, :routeKey, :originLat, :originLng, :destLat, :destLng,
                             :company, :carClass, :provider, :baseRateCents, :taxesAndFeesCents,
                             :totalRentalCents, :pickupAt, :returnAt, :rentalDays, :stale, :fetchedAt)
                        on conflict (snapshot_id) do nothing
                        """)
                .param("snapshotId", snapshot.getSnapshotId())
                .param("routeKey", snapshot.getRouteKey())
                .param("originLat", snapshot.getOriginLat())
                .param("originLng", snapshot.getOriginLng())
                .param("destLat", snapshot.getDestLat())
                .param("destLng", snapshot.getDestLng())
                .param("company", snapshot.getCompany())
                .param("carClass", snapshot.getCarClass())
                .param("provider", snapshot.getProvider())
                .param("baseRateCents", snapshot.getBaseRateCents())
                .param("taxesAndFeesCents", snapshot.getTaxesAndFeesCents())
                .param("totalRentalCents", snapshot.getTotalRentalCents())
                .param("pickupAt", OffsetDateTime.ofInstant(snapshot.getPickupAt(), ZoneOffset.UTC))
                .param("returnAt", OffsetDateTime.ofInstant(snapshot.getReturnAt(), ZoneOffset.UTC))
                .param("rentalDays", snapshot.getRentalDays())
                .param("stale", snapshot.getStale())
                .param("fetchedAt", OffsetDateTime.ofInstant(snapshot.getFetchedAt(), ZoneOffset.UTC))
                .update();
    }
}
