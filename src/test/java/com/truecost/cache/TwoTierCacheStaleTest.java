package com.truecost.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.testcontainers.RedisContainer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves stale while revalidate from docs/design/aggregation-caching-design.md section 4, the PLAN
 * Phase 5 acceptance criterion that a stale response carries the stale flag and a background refresh
 * lands within twice the logical TTL. A short logical TTL keeps the test fast, the physical Redis
 * TTL is twice that, so an entry past its logical TTL is still present in Redis for the whole
 * second span, which is exactly what gives the stale read something to serve while the refresh runs.
 */
@Testcontainers
class TwoTierCacheStaleTest {

    private static final Duration LOGICAL_TTL = Duration.ofMillis(500);
    private static final TypeReference<Payload> PAYLOAD_TYPE = new TypeReference<>() {
    };
    private static final String KEY = "fuel:v1:price:test-region";

    @Container
    static RedisContainer redis = new RedisContainer(DockerImageName.parse("redis:7"));

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void staleReadServesTheStaleValueAndTriggersARefreshThatLandsWithinTwiceTheTtl() throws Exception {
        TwoTierCache cache = new TwoTierCache(redisTemplate, new ObjectMapper(), new RedisLock(redisTemplate),
                Executors.newVirtualThreadPerTaskExecutor(), new SimpleMeterRegistry());
        AtomicInteger version = new AtomicInteger();
        Supplier<Payload> loader = () -> new Payload("v" + version.incrementAndGet());

        CacheResult<Payload> cold = cache.get(KEY, LOGICAL_TTL, PAYLOAD_TYPE, loader);
        assertThat(cold.freshness()).isEqualTo(DataFreshness.FRESH);
        assertThat(cold.value().data()).isEqualTo("v1");

        Thread.sleep(LOGICAL_TTL.toMillis() + 100);

        CacheResult<Payload> stale = cache.get(KEY, LOGICAL_TTL, PAYLOAD_TYPE, loader);
        assertThat(stale.freshness()).isEqualTo(DataFreshness.STALE);
        assertThat(stale.value().data())
                .as("the stale window serves the old value immediately, never blocking on the refresh")
                .isEqualTo("v1");

        Payload refreshed = awaitFresh(cache, loader);
        assertThat(refreshed.data())
                .as("the background refresh reloaded a new value within the second logical TTL span")
                .isEqualTo("v2");
        assertThat(version.get())
                .as("exactly one cold load and one background refresh, the singleflight prevents redundant refreshes")
                .isEqualTo(2);
    }

    private Payload awaitFresh(TwoTierCache cache, Supplier<Payload> loader) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            CacheResult<Payload> result = cache.get(KEY, LOGICAL_TTL, PAYLOAD_TYPE, loader);
            if (result.freshness() == DataFreshness.FRESH) {
                return result.value();
            }
            Thread.sleep(25);
        }
        throw new AssertionError("background refresh did not land within twice the logical TTL");
    }

    record Payload(String data) {
    }
}
