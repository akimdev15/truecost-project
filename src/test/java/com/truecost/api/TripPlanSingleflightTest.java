package com.truecost.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.redis.testcontainers.RedisContainer;
import com.truecost.api.dto.TripPlanRequest;
import com.truecost.seed.SeedLoader;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The PLAN Phase 5 WireMock counted acceptance test. Fires fifty concurrent identical trip plan
 * requests at the live endpoint with the routing, fuel, and rental upstreams all stubbed, and
 * proves each upstream is called exactly the number of distinct cache keys the trip needs, never
 * once per request. That is the keyed singleflight plus cross instance Redis lock guarantee from
 * docs/design/aggregation-caching-design.md section 3 observed end to end through HTTP, the
 * complement to TwoTierCacheConcurrencyTest which proves the same coalescing at the cache unit
 * level. OSRM is hit twice, once for each leg's route key, EIA and RapidAPI once each, rather than
 * fifty times apiece.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TripPlanSingleflightTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final int CONCURRENCY = 50;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    @Container
    @ServiceConnection
    static RedisContainer redis = new RedisContainer(DockerImageName.parse("redis:7"));

    static WireMockServer upstreams = new WireMockServer(options().dynamicPort());

    static {
        upstreams.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + upstreams.port();
        registry.add("truecost.osrm.base-url", () -> base);
        registry.add("truecost.providers.fuel.eia.enabled", () -> "true");
        registry.add("truecost.providers.fuel.eia.base-url", () -> base + "/eia/data/");
        registry.add("truecost.providers.fuel.eia.api-key", () -> "test-key");
        registry.add("truecost.providers.rental.rapidapi.enabled", () -> "true");
        registry.add("truecost.providers.rental.rapidapi.base-url", () -> base + "/rapid");
        registry.add("truecost.providers.rental.rapidapi.api-key", () -> "test-key");
        registry.add("truecost.providers.hotel.amadeus.enabled", () -> "false");
    }

    @Autowired
    private SeedLoader seedLoader;

    @Autowired
    private TestRestTemplate restTemplate;

    private ExecutorService drivers;

    @BeforeAll
    void seedOnce() {
        seedLoader.loadAll();
    }

    /**
     * A fixed upstream delay holds each key's loader in flight long enough that all fifty
     * concurrently released requests are waiting on the same in flight future when they reach the
     * singleflight, which is what makes the exactly one fetch per key count deterministic rather
     * than splitting into several coalescing waves under an instant stub. The delay is well inside
     * TripPlanner's overall deadline, so no field is dropped as unavailable.
     */
    private static final int UPSTREAM_DELAY_MILLIS = 300;

    @BeforeEach
    void stubUpstreams() {
        upstreams.resetAll();
        upstreams.stubFor(get(urlPathMatching("/route/v1/driving/.*")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withFixedDelay(UPSTREAM_DELAY_MILLIS).withBody(osrmBody())));
        upstreams.stubFor(get(urlPathMatching("/eia/data/.*")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withFixedDelay(UPSTREAM_DELAY_MILLIS)
                .withBody("{\"response\":{\"data\":[{\"period\":\"2026-07-14\",\"value\":\"3.40\"}]}}")));
        upstreams.stubFor(get(urlPathMatching("/rapid/.*")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withFixedDelay(UPSTREAM_DELAY_MILLIS).withBody("{\"results\":[]}")));
    }

    @Test
    void fiftyConcurrentIdenticalRequestsCauseOneUpstreamFetchPerCacheKey() throws Exception {
        TripPlanRequest request = new TripPlanRequest(
                40.7505, -73.9934, "EWR",
                39.9500, -75.1600, "PHL",
                nyInstant(2026, 8, 7, 18, 0),
                nyInstant(2026, 8, 9, 14, 0),
                false, "MIDSIZE", null);

        drivers = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch ready = new CountDownLatch(CONCURRENCY);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<HttpStatusCode>> futures = new ArrayList<>();
        for (int i = 0; i < CONCURRENCY; i++) {
            futures.add(drivers.submit(() -> {
                ready.countDown();
                go.await();
                return restTemplate.postForEntity("/api/v1/trips/plan", request, String.class).getStatusCode();
            }));
        }
        ready.await();
        go.countDown();

        for (Future<HttpStatusCode> future : futures) {
            assertThat(future.get().is2xxSuccessful()).isTrue();
        }

        assertThat(countRequests("/route/v1/driving/.*"))
                .as("both leg route keys fetched once each, not once per request")
                .isEqualTo(2);
        assertThat(countRequests("/eia/data/.*"))
                .as("the single fuel price key fetched once for all fifty requests")
                .isEqualTo(1);
        assertThat(countRequests("/rapid/.*"))
                .as("the single rental key for this car class fetched once for all fifty requests")
                .isEqualTo(1);
    }

    @AfterAll
    void shutdownDrivers() {
        if (drivers != null) {
            drivers.shutdownNow();
        }
    }

    private int countRequests(String urlPattern) {
        return upstreams.countRequestsMatching(getRequestedFor(urlPathMatching(urlPattern)).build()).getCount();
    }

    private static Instant nyInstant(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, NEW_YORK).toInstant();
    }

    private static String osrmBody() {
        List<double[]> path = List.of(
                new double[] {40.7505, -73.9934},
                new double[] {40.68678, -74.16506},
                new double[] {39.9500, -75.1600});
        return "{\"code\":\"Ok\",\"routes\":[{\"distance\":150000.0,\"duration\":7200.0,\"geometry\":\""
                + encodePolyline(path) + "\",\"legs\":[{\"annotation\":{\"duration\":[3600,3600]}}]}]}";
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
