package com.truecost.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.redis.testcontainers.RedisContainer;
import com.truecost.api.dto.RentalRateOverride;
import com.truecost.api.dto.TripPlanRequest;
import com.truecost.api.dto.TripPlanResponse;
import com.truecost.seed.SeedLoader;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves the additive rentalRateOverrides path on POST /api/v1/trips/plan end to end, per tonight's
 * change: a caller can supply their own rental price per company, bypassing every
 * RentalQuoteProvider for the rental leg, while the rest of the pipeline, tolls, congestion, fuel,
 * and hotel, still runs unchanged. Reuses the same Manhattan to Philadelphia via New Jersey
 * Turnpike OSRM stub as TripPlanSnapshotTest so the toll strategy engine has a real seeded crossing
 * to price against.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TripPlanUserSuppliedRateTest {

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
    void userSuppliedRatesReplaceProviderQuotesForBothCompanies() throws Exception {
        List<RentalRateOverride> overrides = List.of(
                new RentalRateOverride("HERTZ", "Hertz", 32_500L),
                new RentalRateOverride("AVIS", "Avis", 28_900L));
        TripPlanRequest request = new TripPlanRequest(
                40.7505, -73.9934, "EWR",
                39.9500, -75.1600, "PHL",
                nyInstant(2026, 7, 24, 18, 0),
                nyInstant(2026, 7, 26, 14, 0),
                false, "MIDSIZE", overrides);

        ResponseEntity<TripPlanResponse> response =
                restTemplate.postForEntity("/api/v1/trips/plan", request, TripPlanResponse.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        TripPlanResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.options()).hasSize(2);

        Map<String, Long> suppliedByCompany = Map.of("HERTZ", 32_500L, "AVIS", 28_900L);
        assertThat(body.options())
                .as("exactly the two overridden companies, no synthetic quotes mixed in")
                .extracting(o -> o.companyCode())
                .containsExactlyInAnyOrder("HERTZ", "AVIS");

        body.options().forEach(option -> {
            assertThat(option.rentalCents())
                    .as("rentalCents for %s must equal the supplied totalCents exactly, not a calibrated figure",
                            option.companyCode())
                    .isEqualTo(suppliedByCompany.get(option.companyCode()));
            assertThat(option.provenance().rentalSource())
                    .as("provenance must reflect the user supplied rate, not a synthetic or live source")
                    .isEqualTo("USER_SUPPLIED");
            assertThat(option.tollTotalCents() + option.tollProgramFeeCents() + option.congestionCents())
                    .as("the toll strategy engine still ran against the real seeded NJTPK crossing")
                    .isGreaterThanOrEqualTo(0L);
            assertThat(option.fuelCents())
                    .as("fuel is still estimated normally")
                    .isGreaterThan(0L);
            assertThat(option.hotelCents())
                    .as("hotel is still resolved normally")
                    .isGreaterThan(0L);
            assertThat(option.provenance().fuelPriceIsFallback())
                    .as("EIA is disabled in this test so fuel legitimately falls back, matching the "
                            + "golden snapshot's partial=true for the same reason, the user supplied "
                            + "rental leg itself introduces no additional partiality")
                    .isTrue();
        });
    }

    @Test
    void overridesPresentWithBlankCarClassReturns400() {
        List<RentalRateOverride> overrides = List.of(new RentalRateOverride("HERTZ", "Hertz", 32_500L));
        TripPlanRequest request = new TripPlanRequest(
                40.7505, -73.9934, "EWR",
                39.9500, -75.1600, "PHL",
                nyInstant(2026, 7, 24, 18, 0),
                nyInstant(2026, 7, 26, 14, 0),
                false, null, overrides);

        ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/trips/plan", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(response.getBody().get("error"))).contains("carClass");
    }

    @Test
    void overrideEntryWithBlankCompanyCodeReturns400() {
        List<RentalRateOverride> overrides = List.of(new RentalRateOverride("", "Hertz", 32_500L));
        TripPlanRequest request = new TripPlanRequest(
                40.7505, -73.9934, "EWR",
                39.9500, -75.1600, "PHL",
                nyInstant(2026, 7, 24, 18, 0),
                nyInstant(2026, 7, 26, 14, 0),
                false, "MIDSIZE", overrides);

        ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/trips/plan", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(response.getBody().get("error"))).contains("companyCode");
    }

    @Test
    void overrideEntryWithZeroTotalCentsReturns400() {
        List<RentalRateOverride> overrides = List.of(new RentalRateOverride("HERTZ", "Hertz", 0L));
        TripPlanRequest request = new TripPlanRequest(
                40.7505, -73.9934, "EWR",
                39.9500, -75.1600, "PHL",
                nyInstant(2026, 7, 24, 18, 0),
                nyInstant(2026, 7, 26, 14, 0),
                false, "MIDSIZE", overrides);

        ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/trips/plan", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(response.getBody().get("error"))).contains("totalCents");
    }

    @Test
    void omittedOverridesStillUsesAutomaticSyntheticPath() throws Exception {
        TripPlanRequest request = new TripPlanRequest(
                40.7505, -73.9934, "EWR",
                39.9500, -75.1600, "PHL",
                nyInstant(2026, 7, 24, 18, 0),
                nyInstant(2026, 7, 26, 14, 0),
                false, "MIDSIZE", null);

        ResponseEntity<TripPlanResponse> response =
                restTemplate.postForEntity("/api/v1/trips/plan", request, TripPlanResponse.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        TripPlanResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.options()).isNotEmpty();
        assertThat(body.options())
                .allSatisfy(option -> assertThat(option.provenance().rentalSource()).isEqualTo("SYNTHETIC"));
    }

    private static Instant nyInstant(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, NEW_YORK).toInstant();
    }

    /**
     * The same fixed OSRM response TripPlanSnapshotTest uses, reproducing the Manhattan to
     * Philadelphia drive through the New Jersey Turnpike so the seeded NJTPK crossing detects and
     * prices, proving the toll strategy engine ran even though the rental leg is user supplied.
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
