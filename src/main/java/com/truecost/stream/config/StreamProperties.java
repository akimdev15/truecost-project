package com.truecost.stream.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stream tuning bound from the truecost.stream namespace, so window, hop, grace, threshold, and
 * prefetch lead time can be varied without recompiling. stateDir is the Kafka Streams local state
 * root, set explicitly so it never falls back to the OS temp directory, which survives clean
 * restarts and can be cleared by the OS mid window. graceMinutes is how long after a window
 * closes a late event is still counted, and prefetchLeadFraction is the fraction of a cache
 * entry's TTL at which the prefetch consumer refreshes it.
 */
@ConfigurationProperties("truecost.stream")
public record StreamProperties(
        String applicationId,
        String stateDir,
        int windowSizeMinutes,
        int hopMinutes,
        int graceMinutes,
        int hotnessThreshold,
        double prefetchLeadFraction) {
}
