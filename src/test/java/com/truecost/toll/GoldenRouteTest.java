package com.truecost.toll;

import static org.assertj.core.api.Assertions.assertThat;

import com.truecost.route.Route;
import com.truecost.seed.SeedLoader;
import com.truecost.toll.RouteFixtures.TimedWp;
import com.truecost.toll.RouteFixtures.Wp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The Phase 2 golden route acceptance tests from PLAN.md. Each expected crossing list below was
 * worked out independently of this code, from real highway network knowledge and the real
 * tolled direction facts docs/design and data/seeds/SOURCES.md already verified in Phase 1,
 * before this test was run against the detector. See the Phase 2 report for the full reasoning.
 * Every route is built from real latitude and longitude points along the actual roads the trip
 * drives, using RouteFixtures.approach so the polyline reproduces the real vehicle bearing at
 * each crossing, standing in for a live OSRM polyline while keeping ./gradlew test self
 * contained per CLAUDE.md's Testcontainers rule. Amounts asserted are the real seeded Phase 1
 * figures from data/seeds/toll_rates.csv, not placeholders.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoldenRouteTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final double HIGHWAY_SPEED_METERS_PER_SECOND = 25.0;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    @Autowired
    private SeedLoader seedLoader;

    @Autowired
    private TollTimeline tollTimeline;

    @BeforeAll
    void seedOnce() {
        seedLoader.loadAll();
    }

    @Test
    void manhattanToPhiladelphiaOutboundCrossesOnlyTheNewJerseyTurnpike() {
        Route route = RouteFixtures.atConstantSpeed(HIGHWAY_SPEED_METERS_PER_SECOND, RouteFixtures.chain(
                RouteFixtures.one(new Wp(40.7505, -73.9934)),
                RouteFixtures.approach(40.76247, -74.00997, 280, 300),
                RouteFixtures.one(new Wp(40.7450, -74.0250)),
                RouteFixtures.approach(40.68678, -74.16506, 200, 300),
                RouteFixtures.one(new Wp(40.5000, -74.4500)),
                RouteFixtures.one(new Wp(39.9500, -75.1600))));

        List<PricedCrossing> priced = tollTimeline.price(route, at(2026, 1, 10, 9, 0), "MIDSIZE");

        assertThat(priced).extracting(PricedCrossing::crossingCode).containsExactly("NJTPK");
        assertThat(priced.getFirst().ezpassCents()).isEqualTo(2188L);
    }

    @Test
    void manhattanToBostonOutboundCrossesOnlyTheRfkBridge() {
        Route route = RouteFixtures.atConstantSpeed(HIGHWAY_SPEED_METERS_PER_SECOND, RouteFixtures.chain(
                RouteFixtures.one(new Wp(40.7648, -73.9550)),
                RouteFixtures.approach(40.7906, -73.9235, 90, 300),
                RouteFixtures.one(new Wp(40.8200, -73.8700)),
                RouteFixtures.one(new Wp(41.3000, -72.9000)),
                RouteFixtures.one(new Wp(41.8200, -71.4100)),
                RouteFixtures.one(new Wp(42.3601, -71.0589))));

        List<PricedCrossing> priced = tollTimeline.price(route, at(2026, 1, 10, 8, 0), "MIDSIZE");

        assertThat(priced).extracting(PricedCrossing::crossingCode).containsExactly("RFK");
        assertThat(priced.getFirst().ezpassCents()).isEqualTo(746L);
    }

    @Test
    void manhattanToDcOutboundCrossesOnlyTheNewJerseyTurnpike() {
        Route route = RouteFixtures.atConstantSpeed(HIGHWAY_SPEED_METERS_PER_SECOND, RouteFixtures.chain(
                RouteFixtures.one(new Wp(40.7505, -73.9934)),
                RouteFixtures.approach(40.76247, -74.00997, 280, 300),
                RouteFixtures.one(new Wp(40.7450, -74.0250)),
                RouteFixtures.approach(40.68678, -74.16506, 200, 300),
                RouteFixtures.one(new Wp(39.9000, -75.2000)),
                RouteFixtures.one(new Wp(39.2904, -76.6122)),
                RouteFixtures.one(new Wp(38.9072, -77.0369))));

        List<PricedCrossing> priced = tollTimeline.price(route, at(2026, 1, 10, 7, 30), "MIDSIZE");

        assertThat(priced).extracting(PricedCrossing::crossingCode).containsExactly("NJTPK");
        assertThat(priced.getFirst().ezpassCents()).isEqualTo(2188L);
    }

    @Test
    void brooklynToMontaukCrossesNothing() {
        Route route = RouteFixtures.atConstantSpeed(HIGHWAY_SPEED_METERS_PER_SECOND, RouteFixtures.chain(
                RouteFixtures.one(new Wp(40.6928, -73.9903)),
                RouteFixtures.one(new Wp(40.7282, -73.7949)),
                RouteFixtures.one(new Wp(40.7700, -73.4000)),
                RouteFixtures.one(new Wp(40.8300, -72.9000)),
                RouteFixtures.one(new Wp(40.9500, -72.4000)),
                RouteFixtures.one(new Wp(41.0362, -71.9543))));

        List<PricedCrossing> priced = tollTimeline.price(route, at(2026, 1, 10, 8, 0), "MIDSIZE");

        assertThat(priced).isEmpty();
    }

    @Test
    void manhattanToHudsonValleyOutboundCrossesHenryHudsonAndThruwayNotCuomoBridge() {
        Route route = RouteFixtures.atConstantSpeed(HIGHWAY_SPEED_METERS_PER_SECOND, RouteFixtures.chain(
                RouteFixtures.one(new Wp(40.8650, -73.9250)),
                RouteFixtures.approach(40.8781, -73.9235, 0, 300),
                RouteFixtures.one(new Wp(40.9100, -73.8700)),
                RouteFixtures.approach(40.9312, -73.8388, 10, 300),
                RouteFixtures.one(new Wp(40.9800, -73.8700)),
                RouteFixtures.approach(41.0762, -73.8823, 255, 300),
                RouteFixtures.one(new Wp(41.0900, -73.9300)),
                RouteFixtures.one(new Wp(41.7476, -74.0862))));

        List<PricedCrossing> priced = tollTimeline.price(route, at(2026, 1, 10, 9, 0), "MIDSIZE");

        assertThat(priced).extracting(PricedCrossing::crossingCode).containsExactly("HENRYHUDSON", "THRUWAY");
        assertThat(amountFor(priced, "HENRYHUDSON")).isEqualTo(342L);
        assertThat(amountFor(priced, "THRUWAY")).isEqualTo(423L);
    }

    @Test
    void hudsonValleyToManhattanReturnCrossesAllThreeIncludingCuomoBridgeEastbound() {
        Route route = RouteFixtures.atConstantSpeed(HIGHWAY_SPEED_METERS_PER_SECOND, RouteFixtures.chain(
                RouteFixtures.one(new Wp(41.7476, -74.0862)),
                RouteFixtures.one(new Wp(41.0900, -73.9300)),
                RouteFixtures.approach(41.0762, -73.8823, 75, 300),
                RouteFixtures.one(new Wp(40.9800, -73.8700)),
                RouteFixtures.approach(40.9312, -73.8388, 190, 300),
                RouteFixtures.one(new Wp(40.9100, -73.8700)),
                RouteFixtures.approach(40.8781, -73.9235, 180, 300),
                RouteFixtures.one(new Wp(40.8650, -73.9250))));

        List<PricedCrossing> priced = tollTimeline.price(route, at(2026, 1, 11, 17, 0), "MIDSIZE");

        assertThat(priced).extracting(PricedCrossing::crossingCode)
                .containsExactly("CUOMOBRIDGE", "THRUWAY", "HENRYHUDSON");
        assertThat(amountFor(priced, "CUOMOBRIDGE")).isEqualTo(725L);
    }

    /**
     * The mandatory peak boundary proof, PLAN.md requires at least one route and departure time
     * straddling a peak versus off peak toll boundary. None of the five named routes toll a time
     * varying crossing on their outbound leg, since the six Port Authority Hudson crossings only
     * toll the into New York direction, so this uses the real return leg of the Philadelphia
     * golden route, retracing the New Jersey Turnpike and the Lincoln Tunnel back into Manhattan,
     * at the real weekend peak boundary from data/seeds/SOURCES.md, eleven in the morning.
     */
    @Test
    void philadelphiaToManhattanReturnPricesLincolnTunnelDifferentlyAcrossTheWeekendPeakBoundary() {
        Instant departure = at(2026, 1, 11, 9, 0);
        Route justBeforeEleven = philadelphiaReturnRoute(7140);
        Route atEleven = philadelphiaReturnRoute(7200);

        List<PricedCrossing> offPeakPriced = tollTimeline.price(justBeforeEleven, departure, "MIDSIZE");
        List<PricedCrossing> peakPriced = tollTimeline.price(atEleven, departure, "MIDSIZE");

        assertThat(amountFor(offPeakPriced, "LINCOLN")).isEqualTo(1479L);
        assertThat(amountFor(peakPriced, "LINCOLN")).isEqualTo(1679L);

        assertThat(amountFor(offPeakPriced, "NJTPK")).isEqualTo(2188L);
        assertThat(amountFor(peakPriced, "NJTPK")).isEqualTo(2188L);
    }

    /** lincolnCumulativeSeconds pins the estimated arrival at the Lincoln Tunnel exactly, 7140 seconds after a nine am departure is ten fifty nine, 7200 is eleven flat. */
    private Route philadelphiaReturnRoute(double lincolnCumulativeSeconds) {
        Wp[] njtpk = RouteFixtures.approach(40.68678, -74.16506, 20, 300);
        Wp[] lincoln = RouteFixtures.approach(40.76247, -74.00997, 100, 300);

        return RouteFixtures.withExplicitTimings(
                new TimedWp(39.9500, -75.1600, 0),
                new TimedWp(40.5000, -74.4500, 1800),
                new TimedWp(njtpk[0].lat(), njtpk[0].lng(), 3540),
                new TimedWp(njtpk[1].lat(), njtpk[1].lng(), 3600),
                new TimedWp(njtpk[2].lat(), njtpk[2].lng(), 3660),
                new TimedWp(40.7450, -74.0250, 7000),
                new TimedWp(lincoln[0].lat(), lincoln[0].lng(), lincolnCumulativeSeconds - 20),
                new TimedWp(lincoln[1].lat(), lincoln[1].lng(), lincolnCumulativeSeconds),
                new TimedWp(lincoln[2].lat(), lincoln[2].lng(), lincolnCumulativeSeconds + 20),
                new TimedWp(40.7505, -73.9934, lincolnCumulativeSeconds + 200));
    }

    private static long amountFor(List<PricedCrossing> priced, String crossingCode) {
        return priced.stream()
                .filter(p -> p.crossingCode().equals(crossingCode))
                .findFirst()
                .orElseThrow(() -> new AssertionError("crossing " + crossingCode + " not found in " + priced))
                .ezpassCents();
    }

    private static Instant at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, NEW_YORK).toInstant();
    }
}
