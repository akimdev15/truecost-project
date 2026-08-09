package com.truecost.stream.config;

import com.truecost.stream.config.TopicsProperties.TopicSpec;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the three event topics in code, built from truecost.kafka.topics properties so
 * partition counts, replication, and retention come from configuration rather than compiled
 * constants. This configuration lives in the prefetcher-only com.truecost.stream package, so only
 * the prefetcher deployable creates these topics and contacts Kafka on startup.
 */
@Configuration
@EnableConfigurationProperties({TopicsProperties.class, StreamProperties.class})
public class TopicsConfig {

    @Bean
    public NewTopic tripSearchesTopic(TopicsProperties properties) {
        return topic(properties.tripSearches());
    }

    @Bean
    public NewTopic hotRoutesTopic(TopicsProperties properties) {
        return topic(properties.hotRoutes());
    }

    @Bean
    public NewTopic quoteSnapshotsTopic(TopicsProperties properties) {
        return topic(properties.quoteSnapshots());
    }

    private static NewTopic topic(TopicSpec spec) {
        return TopicBuilder.name(spec.name())
                .partitions(spec.partitions())
                .replicas(spec.replicationFactor())
                .config("retention.ms", Long.toString(spec.retentionMs()))
                .config("cleanup.policy", "delete")
                .build();
    }
}
