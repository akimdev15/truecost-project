package com.truecost.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.redis.testcontainers.RedisContainer;
import com.truecost.api.dto.TripPlanRequest;
import com.truecost.seed.SeedLoader;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The PLAN Phase 5 snapshot acceptance test. Drives one golden weekend trip, Manhattan to
 * Philadelphia and back, through the whole endpoint against real Postgres and Redis, with OSRM
 * stubbed to a fixed polyline that crosses the New Jersey Turnpike so the toll strategy engine,
 * the thesis centerpiece, runs on a real seeded crossing rather than an empty route. Every other
 * input is deterministic, the synthetic rental and hotel providers are seed derived and the EIA
 * fuel connector is disabled so fuel uses the static fallback price, so the full response JSON is
 * a fixed function of the request and the seed data. The response is pinned against a golden file
 * so any change to pricing, ranking, or the response tree is caught as a regression.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TripPlanSnapshotTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    @Container
    @ServiceConnection
    static RedisContainer redis = new RedisContainer(DockerImageName.parse("redis:7"));

    static WireMockServer osrm = new WireMockServer(options().dynamicPort());

    static {
        osrm.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("truecost.osrm.base-url", () -> "http://localhost:" + osrm.port());
        registry.add("truecost.providers.fuel.eia.enabled", () -> "false");
        registry.add("truecost.providers.rental.rapidapi.enabled", () -> "false");
        registry.add("truecost.providers.hotel.amadeus.enabled", () -> "false");
    }

    @Autowired
    private SeedLoader seedLoader;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeAll
    void seedOnce() {
        seedLoader.loadAll();
    }

    @BeforeEach
    void stubOsrm() {
        osrm.resetAll();
        osrm.stubFor(get(urlPathMatching("/route/v1/driving/.*")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(osrmResponseBody())));
    }

    @Test
    void goldenWeekendTripPinsTheFullResponse() throws Exception {
        TripPlanRequest request = new TripPlanRequest(
                40.7505, -73.9934, "EWR",
                39.9500, -75.1600, "PHL",
                nyInstant(2026, 7, 24, 18, 0),
                nyInstant(2026, 7, 26, 14, 0),
                false, "MIDSIZE", null);

        ResponseEntity<String> response =
                restTemplate.postForEntity("/api/v1/trips/plan", request, String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

        String expected = canonical(readGolden());
        String actual = canonical(response.getBody());
        assertThat(actual)
                .as("the golden weekend trip response must match snapshots/trip-plan-golden.json, "
                        + "regenerate the golden file only when a pricing or response change is intended")
                .isEqualTo(expected);

        assertThat(strategyChosen("COMPANY_UNLIMITED"))
                .as("the business metric counts the winning toll strategy per option, the golden "
                        + "scenario has company unlimited winners")
                .isPositive();
        assertThat(strategyChosen("COMPANY_PER_CROSSING"))
                .as("and the counter splits across strategies, Enterprise wins per-crossing here")
                .isPositive();
    }

    private double strategyChosen(String kind) {
        var counter = meterRegistry.find("truecost.strategy.chosen").tag("kind", kind).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private String canonical(String json) throws Exception {
        JsonNode tree = objectMapper.readTree(json);
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
    }

    private String readGolden() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/snapshots/trip-plan-golden.json")) {
            if (in == null) {
                throw new IllegalStateException("golden snapshot resource /snapshots/trip-plan-golden.json not found");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Instant nyInstant(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, NEW_YORK).toInstant();
    }

    /**
     * A fixed OSRM response whose polyline reproduces the Manhattan to Philadelphia drive through
     * the New Jersey Turnpike, the same crossing coordinate and tolled bearing the Phase 2 golden
     * route test uses, so the seeded NJTPK crossing detects and prices. distance and duration are
     * fixed so the fuel estimate and arrival timing are deterministic. The same body serves both
     * legs, the outbound and the return, since the stub matches any coordinate path.
     */
    private static String osrmResponseBody() {
        double njtpkLat = 40.68678;
        double njtpkLng = -74.16506;
        double tolledBearing = 200;
        double legMeters = 300;
        double[] before = offset(njtpkLat, njtpkLng, (tolledBearing + 180) % 360, legMeters);
        double[] after = offset(njtpkLat, njtpkLng, tolledBearing, legMeters);

        List<double[]> path = new ArrayList<>();
        path.add(new double[] {40.7505, -73.9934});
        path.add(before);
        path.add(new double[] {njtpkLat, njtpkLng});
        path.add(after);
        path.add(new double[] {39.9500, -75.1600});

        String geometry = encodePolyline(path);
        StringBuilder durations = new StringBuilder();
        for (int i = 0; i < path.size() - 1; i++) {
            durations.append(i == 0 ? "" : ",").append(1800);
        }
        return "{\"code\":\"Ok\",\"routes\":[{\"distance\":150000.0,\"duration\":7200.0,\"geometry\":\""
                + geometry + "\",\"legs\":[{\"annotation\":{\"duration\":[" + durations + "]}}]}]}";
    }

    private static double[] offset(double lat, double lng, double bearingDeg, double distanceMeters) {
        double bearingRad = Math.toRadians(bearingDeg);
        double deltaLat = (distanceMeters * Math.cos(bearingRad)) / 111_320.0;
        double deltaLng = (distanceMeters * Math.sin(bearingRad)) / (111_320.0 * Math.cos(Math.toRadians(lat)));
        return new double[] {lat + deltaLat, lng + deltaLng};
    }

    private static String encodePolyline(List<double[]> path) {
        long lastLat = 0;
        long lastLng = 0;
        StringBuilder encoded = new StringBuilder();
        for (double[] point : path) {
            long lat = Math.round(point[0] * 1e5);
            long lng = Math.round(point[1] * 1e5);
            encodeValue(lat - lastLat, encoded);
            encodeValue(lng - lastLng, encoded);
            lastLat = lat;
            lastLng = lng;
        }
        return encoded.toString();
    }

    private static void encodeValue(long value, StringBuilder encoded) {
        long shifted = value < 0 ? ~(value << 1) : (value << 1);
        while (shifted >= 0x20) {
            encoded.append((char) ((int) ((0x20 | (shifted & 0x1f)) + 63)));
            shifted >>= 5;
        }
        encoded.append((char) ((int) (shifted + 63)));
    }
}
