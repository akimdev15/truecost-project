package com.truecost.toll;

import static org.assertj.core.api.Assertions.assertThat;

import com.truecost.route.Route;
import com.truecost.seed.SeedLoader;
import com.truecost.toll.RouteFixtures.Wp;
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
 * The two dedicated detector proofs PLAN.md's Phase 2 acceptance criteria require beyond the
 * golden routes, a near miss detects nothing, and wrong direction travel on a FORWARD only
 * crossing detects nothing. Both use the George Washington Bridge, code GWB, latitude 40.8517,
 * longitude -73.9527, travel_bearing_deg 100, tolled FORWARD only, the eastbound into New York
 * direction, seeded in data/seeds/crossings.csv.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrossingDetectorTest {

    private static final double GWB_LAT = 40.8517;
    private static final double GWB_LNG = -73.9527;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    @Autowired
    private SeedLoader seedLoader;

    @Autowired
    private CrossingDetector crossingDetector;

    @BeforeAll
    void seedOnce() {
        seedLoader.loadAll();
    }

    @Test
    void routePassingNearButNotOverTheCrossingDetectsNothing() {
        // A path offset about 150 meters east of GWB, well past the 50 meter threshold, heading
        // in the same eastbound direction the real tolled crossing uses, so distance alone, not
        // direction, is what should suppress the match.
        Wp offsetPoint = new Wp(GWB_LAT, GWB_LNG + 0.00178);
        Route route = RouteFixtures.atConstantSpeed(25.0,
                new Wp(GWB_LAT - 0.004, GWB_LNG + 0.0020),
                offsetPoint,
                new Wp(GWB_LAT + 0.004, GWB_LNG + 0.0060));

        List<DetectedCrossing> detected = crossingDetector.detect(route);

        assertThat(detected).noneMatch(c -> c.crossingCode().equals("GWB"));
    }

    @Test
    void wrongDirectionTravelOnAForwardOnlyCrossingDetectsNothing() {
        // Straight through the exact GWB coordinate, but westbound, Manhattan to New Jersey,
        // the untolled direction since GWB only tolls the eastbound, into New York, direction.
        Wp[] westbound = RouteFixtures.approach(GWB_LAT, GWB_LNG, 280, 300);
        Route route = RouteFixtures.atConstantSpeed(25.0, westbound);

        List<DetectedCrossing> detected = crossingDetector.detect(route);

        assertThat(detected).noneMatch(c -> c.crossingCode().equals("GWB"));
    }

    @Test
    void correctDirectionTravelOnAForwardOnlyCrossingIsDetected() {
        // The positive control for the wrong direction test above, same exact coordinate,
        // eastbound, New Jersey to New York, the tolled direction.
        Wp[] eastbound = RouteFixtures.approach(GWB_LAT, GWB_LNG, 100, 300);
        Route route = RouteFixtures.atConstantSpeed(25.0, eastbound);

        List<DetectedCrossing> detected = crossingDetector.detect(route);

        assertThat(detected).extracting(DetectedCrossing::crossingCode).containsExactly("GWB");
    }
}
