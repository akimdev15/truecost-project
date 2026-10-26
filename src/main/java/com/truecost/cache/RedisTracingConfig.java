package com.truecost.cache;

import io.lettuce.core.tracing.MicrometerTracing;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.data.redis.ClientResourcesBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot does not instrument Lettuce on its own, so without this the Redis calls on the
 * cache path are missing from the trace waterfall and cold path time cannot be attributed.
 */
@Configuration
public class RedisTracingConfig {

    private static final String SERVICE_NAME = "redis";

    @Bean
    public ClientResourcesBuilderCustomizer lettuceObservationCustomizer(ObservationRegistry registry) {
        return builder -> builder.tracing(new MicrometerTracing(registry, SERVICE_NAME));
    }
}
