package com.truecost.stream.topology;

import com.truecost.events.HotRouteSignal;
import com.truecost.events.SearchRequested;
import com.truecost.stream.config.StreamProperties;
import java.time.Duration;
import java.time.Instant;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.Suppressed;
import org.apache.kafka.streams.kstream.Suppressed.BufferConfig;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.state.WindowStore;

/**
 * Counts are suppressed until each window closes rather than filtered on the exact threshold
 * value, because Kafka Streams record caching can coalesce updates and skip past the threshold
 * count entirely, so a filter would sometimes never emit. A route searched heavily near one
 * instant can fall into up to three overlapping hopping windows and emit up to three signals,
 * which is expected since the prefetch consumer is idempotent.
 */
public final class HotRouteTopology {

    static final String COUNT_STORE = "hot-route-search-counts";
    private static final String KEY_DELIMITER = "|";

    private HotRouteTopology() {
    }

    public static void build(StreamsBuilder builder, StreamProperties props, String sourceTopic,
            String sinkTopic, Serde<SearchRequested> searchSerde, Serde<HotRouteSignal> signalSerde) {
        Duration windowSize = Duration.ofMinutes(props.windowSizeMinutes());
        Duration hop = Duration.ofMinutes(props.hopMinutes());
        Duration grace = Duration.ofMinutes(props.graceMinutes());
        int threshold = props.hotnessThreshold();

        TimeWindows windows = TimeWindows.ofSizeAndGrace(windowSize, grace).advanceBy(hop);
        Serde<RouteWindowAggregate> aggregateSerde = new JsonValueSerde<>(RouteWindowAggregate.class);

        builder.stream(sourceTopic, Consumed.with(Serdes.String(), searchSerde)
                        .withTimestampExtractor(new SearchOccurredAtExtractor()))
                .selectKey((key, search) -> search.getRouteKey() + KEY_DELIMITER + search.getDateBucket())
                .groupByKey(Grouped.with(Serdes.String(), searchSerde))
                .windowedBy(windows)
                .aggregate(RouteWindowAggregate::empty,
                        (key, search, aggregate) -> aggregate.add(search),
                        Materialized.<String, RouteWindowAggregate, WindowStore<Bytes, byte[]>>as(COUNT_STORE)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(aggregateSerde)
                                .withRetention(windowSize.plus(grace).plus(hop)))
                .suppress(Suppressed.untilWindowCloses(BufferConfig.unbounded()))
                .toStream()
                .filter((windowedKey, aggregate) -> aggregate != null
                        && aggregate.searchCount() >= threshold)
                .map((windowedKey, aggregate) -> KeyValue.pair(
                        routeKeyOf(windowedKey.key()), toSignal(windowedKey, aggregate, threshold)))
                .to(sinkTopic, Produced.with(Serdes.String(), signalSerde));
    }

    private static HotRouteSignal toSignal(Windowed<String> windowedKey, RouteWindowAggregate aggregate,
            int threshold) {
        return HotRouteSignal.newBuilder()
                .setRouteKey(routeKeyOf(windowedKey.key()))
                .setDateBucket(dateBucketOf(windowedKey.key()))
                .setWindowStart(windowedKey.window().startTime())
                .setWindowEnd(windowedKey.window().endTime())
                .setSearchCount(aggregate.searchCount())
                .setThreshold(threshold)
                .setOriginLat(aggregate.originLat())
                .setOriginLng(aggregate.originLng())
                .setDestLat(aggregate.destLat())
                .setDestLng(aggregate.destLng())
                .setDepartureAt(Instant.ofEpochMilli(aggregate.departureAt()))
                .setReturnAt(Instant.ofEpochMilli(aggregate.returnAt()))
                .setCarClass(aggregate.carClass())
                .setPickupLocationCode(aggregate.pickupLocationCode())
                .setDestinationCode(aggregate.destinationCode())
                .setEmittedAt(Instant.now())
                .build();
    }

    private static String routeKeyOf(String compositeKey) {
        return compositeKey.substring(0, compositeKey.lastIndexOf(KEY_DELIMITER));
    }

    private static String dateBucketOf(String compositeKey) {
        return compositeKey.substring(compositeKey.lastIndexOf(KEY_DELIMITER) + 1);
    }
}
