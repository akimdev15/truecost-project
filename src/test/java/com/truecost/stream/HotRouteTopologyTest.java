package com.truecost.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.truecost.events.HotRouteSignal;
import com.truecost.events.SearchRequested;
import com.truecost.stream.config.StreamProperties;
import com.truecost.stream.topology.HotRouteTopology;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The PLAN Phase 6 TopologyTestDriver acceptance tests, covering windowing, the hotness threshold,
 * and dedup of repeated hot emissions, with no broker. Time is driven purely through each search's
 * occurredAt, which SearchOccurredAtExtractor uses as the event time, so the hopping window and the
 * suppress until close behavior are exercised deterministically.
 */
class HotRouteTopologyTest {

    private static final String SOURCE = "trip-searches";
    private static final String SINK = "hot-routes";
    private static final String ROUTE = "dr5ru-dr4e3";
    private static final String DATE_BUCKET = "2026-08-14";
    private static final Instant BASE = Instant.parse("2026-08-14T12:00:00Z");
    private static final long MINUTE = 60_000L;
    private static final int THRESHOLD = 5;

    private TopologyTestDriver driver;
    private TestInputTopic<String, SearchRequested> searches;
    private TestOutputTopic<String, HotRouteSignal> hotRoutes;

    @BeforeEach
    void setUp() {
        Serde<SearchRequested> searchSerde = new SpecificAvroTestSerde<>(SearchRequested.getClassSchema());
        Serde<HotRouteSignal> signalSerde = new SpecificAvroTestSerde<>(HotRouteSignal.getClassSchema());

        StreamProperties props = new StreamProperties("test", 15, 5, 1, THRESHOLD, 0.8);
        StreamsBuilder builder = new StreamsBuilder();
        HotRouteTopology.build(builder, props, SOURCE, SINK, searchSerde, signalSerde);

        Properties config = new Properties();
        config.put(StreamsConfig.APPLICATION_ID_CONFIG, "hot-route-topology-test");
        config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        config.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

        driver = new TopologyTestDriver(builder.build(), config);
        searches = driver.createInputTopic(SOURCE, new StringSerializer(), searchSerde.serializer());
        hotRoutes = driver.createOutputTopic(SINK, new StringDeserializer(), signalSerde.deserializer());
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    @Test
    void aRouteReachingThresholdInAWindowEmitsOnePerOverlappingWindowNotOncePerSearch() {
        for (int i = 0; i < 8; i++) {
            pipe(ROUTE, 20);
        }
        advanceClockPastWindows(40);

        List<HotRouteSignal> signals = hotRoutes.readValuesToList();

        assertThat(signals)
                .as("a fifteen minute window advancing every five minutes covers one instant with "
                        + "three overlapping windows, so a heavy burst emits three signals")
                .hasSize(3);
        assertThat(signals).allSatisfy(signal -> {
            assertThat(signal.getRouteKey()).isEqualTo(ROUTE);
            assertThat(signal.getDateBucket()).isEqualTo(DATE_BUCKET);
            assertThat(signal.getThreshold()).isEqualTo(THRESHOLD);
            assertThat(signal.getSearchCount())
                    .as("suppress forwards one final aggregate per window, all eight searches, "
                            + "never one emission per counted search past the threshold")
                    .isEqualTo(8L);
        });
        assertThat(signals).extracting(s -> s.getWindowStart().toEpochMilli())
                .doesNotHaveDuplicates();
    }

    @Test
    void aRouteBelowThresholdInEveryWindowEmitsNothing() {
        for (int i = 0; i < THRESHOLD - 1; i++) {
            pipe(ROUTE, 20);
        }
        advanceClockPastWindows(40);

        assertThat(hotRoutes.readValuesToList()).isEmpty();
    }

    @Test
    void searchesSpreadAcrossNonOverlappingWindowsNeverAccumulateToThreshold() {
        pipe(ROUTE, 20);
        pipe(ROUTE, 40);
        pipe(ROUTE, 60);
        pipe(ROUTE, 80);
        pipe(ROUTE, 100);
        advanceClockPastWindows(140);

        assertThat(hotRoutes.readValuesToList())
                .as("five searches twenty minutes apart never share a fifteen minute window, so no "
                        + "window ever counts more than one and none reaches the threshold")
                .isEmpty();
    }

    private void pipe(String routeKey, long atMinute) {
        searches.pipeInput(routeKey, search(routeKey, BASE.plusMillis(atMinute * MINUTE)));
    }

    private void advanceClockPastWindows(long atMinute) {
        searches.pipeInput("tick-route", search("tick-route", BASE.plusMillis(atMinute * MINUTE)));
    }

    private static SearchRequested search(String routeKey, Instant occurredAt) {
        return SearchRequested.newBuilder()
                .setEventId(UUID.randomUUID())
                .setRouteKey(routeKey)
                .setDateBucket(DATE_BUCKET)
                .setOriginLat(40.7505)
                .setOriginLng(-73.9934)
                .setDestLat(39.95)
                .setDestLng(-75.16)
                .setDepartureAt(occurredAt.plusMillis(6 * 60 * MINUTE))
                .setReturnAt(occurredAt.plusMillis(54 * 60 * MINUTE))
                .setHasPersonalEzpass(false)
                .setCarClass("MIDSIZE")
                .setOccurredAt(occurredAt)
                .build();
    }
}
