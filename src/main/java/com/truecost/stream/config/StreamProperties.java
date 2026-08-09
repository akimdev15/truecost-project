package com.truecost.stream.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stream tuning bound from the truecost.stream namespace, so window, hop, grace, threshold, and
 * prefetch lead time can be varied without recompiling. graceMinutes is how long after a window
 * closes a late event is still counted, and prefetchLeadFraction is the fraction of a cache
 * entry's TTL at which the prefetch consumer refreshes it.
 */
@ConfigurationProperties("truecost.stream")
public record StreamProperties(
        String applicationId,
        int windowSizeMinutes,
        int hopMinutes,
        int graceMinutes,
        int hotnessThreshold,
        double prefetchLeadFraction) {
}
