package com.truecost.stream.topology;

import com.truecost.events.HotRouteSignal;
import com.truecost.events.SearchRequested;
import com.truecost.stream.config.StreamProperties;
import com.truecost.stream.config.TopicsProperties;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.streams.StreamsBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Registers the hot route topology on the Spring managed StreamsBuilder, kept separate from
 * StreamsTopologyConfig because that class defines the KafkaStreamsConfiguration bean the
 * StreamsBuilder autoconfiguration depends on, and injecting StreamsBuilder there would form a
 * bean creation cycle.
 */
@Component
public class HotRoutePipeline {

    @Autowired
    public void register(StreamsBuilder builder, StreamProperties props, TopicsProperties topics,
            Serde<SearchRequested> searchRequestedSerde, Serde<HotRouteSignal> hotRouteSignalSerde) {
        HotRouteTopology.build(builder, props, topics.tripSearches().name(), topics.hotRoutes().name(),
                searchRequestedSerde, hotRouteSignalSerde);
    }
}
