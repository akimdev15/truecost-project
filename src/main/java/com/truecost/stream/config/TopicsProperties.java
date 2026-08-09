package com.truecost.stream.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Topic parameters bound from the truecost.kafka.topics namespace, so the dev profile and the
 * kind overlay differ by overriding properties rather than by changing code. Partition counts and
 * the replication factor declared here are turned into KafkaAdmin NewTopic beans by TopicsConfig.
 */
@ConfigurationProperties("truecost.kafka.topics")
public record TopicsProperties(
        TopicSpec tripSearches,
        TopicSpec hotRoutes,
        TopicSpec quoteSnapshots) {

    public record TopicSpec(String name, int partitions, short replicationFactor, long retentionMs) {
    }
}
