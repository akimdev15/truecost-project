package com.truecost.toll;

import static org.assertj.core.api.Assertions.assertThat;

import com.truecost.domain.Money;
import com.truecost.route.Route;
import com.truecost.seed.SeedLoader;
import com.truecost.toll.RouteFixtures.Wp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
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
 * None of the five PLAN.md golden routes enter the Congestion Relief Zone, they are all
 * departures from New York, so congestion zone entry, the peak tunnel credit, and the FDR Drive
 * or Route 9A carve out get their own dedicated proofs here, entering Manhattan from New Jersey
 * through the Lincoln Tunnel, one of the four zone entry tunnels the real published schedule
 * grants a credit for, confirmed in data/seeds/SOURCES.md.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CongestionPricerTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    @Autowired
    private SeedLoader seedLoader;

    @Autowired
    private CrossingDetector crossingDetector;

    @Autowired
    private CongestionPricer congestionPricer;

    @BeforeAll
    void seedOnce() {
        seedLoader.loadAll();
    }

    @Test
    void enteringViaLincolnTunnelDuringPeakAppliesTheTunnelCredit() {
        Route route = lincolnTunnelEntryRoute();
        Instant departure = at(2026, 1, 10, 13, 0);

        Optional<CongestionCharge> charge = congestionPricer.price(
                route, crossingDetector.detect(route), departure, "PASSENGER", "EZPASS");

        assertThat(charge).isPresent();
        assertThat(charge.get().period()).isEqualTo("PEAK");
        assertThat(charge.get().scheduleAmount()).isEqualTo(Money.ofCents(900));
        assertThat(charge.get().creditAmount()).isEqualTo(Money.ofCents(300));
        assertThat(charge.get().netAmount()).isEqualTo(Money.ofCents(600));
        assertThat(charge.get().creditCrossingCode()).isEqualTo("LINCOLN");
    }

    @Test
    void enteringViaLincolnTunnelOvernightAppliesNoCredit() {
        Route route = lincolnTunnelEntryRoute();
        Instant departure = at(2026, 1, 10, 2, 0);

        Optional<CongestionCharge> charge = congestionPricer.price(
                route, crossingDetector.detect(route), departure, "PASSENGER", "EZPASS");

        assertThat(charge).isPresent();
        assertThat(charge.get().period()).isEqualTo("OVERNIGHT");
        assertThat(charge.get().scheduleAmount()).isEqualTo(Money.ofCents(225));
        assertThat(charge.get().creditAmount()).isEqualTo(Money.ZERO);
        assertThat(charge.get().netAmount()).isEqualTo(Money.ofCents(225));
        assertThat(charge.get().creditCrossingCode()).isNull();
    }

    @Test
    void stayingOnTheWestSideHighwayCorridorNeverEntersTheZone() {
        Route route = RouteFixtures.atConstantSpeed(25.0,
                new Wp(40.7712, -74.0050),
                new Wp(40.7480, -74.0120),
                new Wp(40.7350, -74.0140),
                new Wp(40.7195, -74.0160));
        Instant departure = at(2026, 1, 10, 13, 0);

        Optional<CongestionCharge> charge = congestionPricer.price(
                route, crossingDetector.detect(route), departure, "PASSENGER", "EZPASS");

        assertThat(charge).isEmpty();
    }

    private static Route lincolnTunnelEntryRoute() {
        return RouteFixtures.atConstantSpeed(25.0, RouteFixtures.chain(
                RouteFixtures.one(new Wp(40.7450, -74.0250)),
                RouteFixtures.approach(40.76247, -74.00997, 100, 300),
                RouteFixtures.one(new Wp(40.7580, -73.9855))));
    }

    private static Instant at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, NEW_YORK).toInstant();
    }
}
