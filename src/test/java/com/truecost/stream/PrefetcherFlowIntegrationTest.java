package com.truecost.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.truecost.events.SearchRequested;
import com.truecost.seed.SeedLoader;
import io.apicurio.registry.resolver.config.SchemaResolverConfig;
import io.apicurio.registry.serde.avro.AvroKafkaSerializer;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import com.redis.testcontainers.RedisContainer;

/**
 * The PLAN Phase 6 integration acceptance test for the prefetcher deployable, covering criteria two
 * and three. It boots PrefetcherApplication with its topology, prefetch consumer, and quote snapshot
 * sink against real Postgres, Redis, and Apicurio plus an embedded broker, then produces twenty
 * SearchRequested events for one route and a later tick that closes the window. With no user request
 * at all, the topology emits a HotRouteSignal, the prefetch consumer warms Redis by planning the
 * trip, and the resulting quotes are captured into the quote_snapshot table. The test asserts a
 * rental cache entry appears in Redis and a snapshot row appears in Postgres.
 *
 * <p>OSRM is deliberately not stubbed, so route and tolls warming fails fast with a connection
 * refused and the plan degrades to a partial result, which still fetches the synthetic rental quotes
 * that warm the rental cache and feed the snapshot. This keeps the test to the containers that
 * matter for the event flow. Route and tolls warming through the same path is covered once OSRM is
 * up, per the runbook.
 */
@SpringBootTest(classes = PrefetcherApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {"trip-searches", "hot-routes", "quote-snapshots"})
@Import(SeedLoader.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrefetcherFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    @Container
    @ServiceConnection
    static RedisContainer redis = new RedisContainer(DockerImageName.parse("redis:7"));

    static final GenericContainer<?> apicurio = new GenericContainer<>("apicurio/apicurio-registry:3.2.6")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/apis/registry/v3/system/info").forPort(8080).forStatusCode(200));

    static {
        apicurio.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
        registry.add("spring.kafka.properties.apicurio.registry.url", PrefetcherFlowIntegrationTest::registryUrl);
        registry.add("truecost.events.enabled", () -> "true");
        registry.add("truecost.stream.window-size-minutes", () -> "1");
        registry.add("truecost.stream.hop-minutes", () -> "1");
        registry.add("truecost.stream.grace-minutes", () -> "0");
        registry.add("truecost.stream.hotness-threshold", () -> "5");
    }

    private static String registryUrl() {
        return "http://" + apicurio.getHost() + ":" + apicurio.getMappedPort(8080) + "/apis/registry/v3";
    }

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private SeedLoader seedLoader;

    @BeforeAll
    void seed() {
        seedLoader.loadAll();
    }

    @Test
    void twentySearchesWarmTheCacheAndCaptureSnapshotsWithNoUserRequest() throws Exception {
        Instant windowInstant = Instant.parse("2026-08-14T18:00:00Z");
        try (Producer<String, SearchRequested> producer = new KafkaProducer<>(producerConfig())) {
            for (int i = 0; i < 20; i++) {
                producer.send(new ProducerRecord<>("trip-searches", "hot-route", hotSearch(windowInstant)));
            }
            // A later search on the same route key advances stream time on the same repartition
            // partition, so the earlier window closes and suppress emits. A tick on a different key
            // could land on a different partition and never advance this route's stream time.
            producer.send(new ProducerRecord<>("trip-searches", "hot-route",
                    hotSearch(windowInstant.plus(Duration.ofMinutes(2)))));
            producer.flush();
        }

        boolean warmed = awaitCondition(() -> !redisTemplate.keys("rental:v1:*").isEmpty());
        assertThat(warmed)
                .as("the prefetch consumer warmed a rental cache entry in Redis with no user request")
                .isTrue();

        boolean captured = awaitCondition(() -> snapshotCount() > 0);
        assertThat(captured)
                .as("fetched quotes were captured into quote_snapshot through the sink")
                .isTrue();

        Map<String, Object> row = jdbcClient.sql(
                        "select route_key, company, total_rental_cents, fetched_at from quote_snapshot limit 1")
                .query().singleRow();
        assertThat(row.get("route_key")).isNotNull();
        assertThat(row.get("company")).isNotNull();
        assertThat((Long) row.get("total_rental_cents")).isPositive();
        assertThat(row.get("fetched_at")).isNotNull();
    }

    private boolean awaitCondition(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(500);
        }
        return condition.getAsBoolean();
    }

    private int snapshotCount() {
        return jdbcClient.sql("select count(*) from quote_snapshot").query(Integer.class).single();
    }

    private Map<String, Object> producerConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, AvroKafkaSerializer.class);
        config.put(SchemaResolverConfig.REGISTRY_URL, registryUrl());
        config.put(SchemaResolverConfig.AUTO_REGISTER_ARTIFACT, "true");
        return config;
    }

    private static SearchRequested hotSearch(Instant occurredAt) {
        return SearchRequested.newBuilder()
                .setEventId(UUID.randomUUID())
                .setRouteKey("dr5ru-dr4e3")
                .setDateBucket("2026-08-14")
                .setOriginLat(40.7505)
                .setOriginLng(-73.9934)
                .setDestLat(39.95)
                .setDestLng(-75.16)
                .setDepartureAt(occurredAt.plus(Duration.ofHours(4)))
                .setReturnAt(occurredAt.plus(Duration.ofHours(48)))
                .setHasPersonalEzpass(false)
                .setCarClass("MIDSIZE")
                .setPickupLocationCode("EWR")
                .setDestinationCode("PHL")
                .setOccurredAt(occurredAt)
                .build();
    }

}
