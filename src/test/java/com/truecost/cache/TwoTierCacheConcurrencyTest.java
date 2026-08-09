package com.truecost.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.testcontainers.RedisContainer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * Proves the keyed singleflight guarantee from docs/design/aggregation-caching-design.md section 3,
 * the PLAN Phase 5 acceptance criterion that many concurrent identical misses cause exactly one
 * upstream fetch. This exercises the cache mechanism directly against a real Testcontainers Redis
 * rather than through the HTTP endpoint, so the loader invocation count is observed precisely with
 * no provider stub bookkeeping in the way. A counting loader stands in for the single upstream call
 * every cache class funnels through.
 */
@Testcontainers
class TwoTierCacheConcurrencyTest {

    private static final int CONCURRENCY = 50;
    private static final Duration TTL = Duration.ofMinutes(15);
    private static final TypeReference<Payload> PAYLOAD_TYPE = new TypeReference<>() {
    };

    @Container
    static RedisContainer redis = new RedisContainer(DockerImageName.parse("redis:7"));

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private ExecutorService drivers;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        drivers = Executors.newFixedThreadPool(CONCURRENCY);
    }

    @AfterEach
    void tearDown() {
        drivers.shutdownNow();
        connectionFactory.destroy();
    }

    @Test
    void fiftyConcurrentIdenticalMissesInOneInstanceRunTheLoaderOnce() throws Exception {
        TwoTierCache cache = newCache();
        AtomicInteger loaderCalls = new AtomicInteger();
        Supplier<Payload> loader = () -> {
            loaderCalls.incrementAndGet();
            return new Payload("loaded");
        };

        List<Payload> results = fanOut(() -> cache.get("rental:v1:test-key", TTL, PAYLOAD_TYPE, loader));

        assertThat(loaderCalls.get()).isEqualTo(1);
        assertThat(results).hasSize(CONCURRENCY).allSatisfy(p -> assertThat(p.data()).isEqualTo("loaded"));
    }

    @Test
    void concurrentIdenticalMissesAcrossTwoInstancesRunTheLoaderOnce() throws Exception {
        TwoTierCache instanceA = newCache();
        TwoTierCache instanceB = newCache();
        AtomicInteger loaderCalls = new AtomicInteger();
        Supplier<Payload> loader = () -> {
            loaderCalls.incrementAndGet();
            return new Payload("loaded");
        };

        List<Payload> results = fanOut(i -> {
            TwoTierCache cache = (i % 2 == 0) ? instanceA : instanceB;
            return cache.get("route:v1:shared-key", TTL, PAYLOAD_TYPE, loader);
        });

        assertThat(loaderCalls.get()).isEqualTo(1);
        assertThat(results).hasSize(CONCURRENCY).allSatisfy(p -> assertThat(p.data()).isEqualTo("loaded"));
    }

    private List<Payload> fanOut(Supplier<CacheResult<Payload>> call) throws Exception {
        return fanOut(i -> call.get());
    }

    private List<Payload> fanOut(java.util.function.IntFunction<CacheResult<Payload>> call) throws Exception {
        CountDownLatch ready = new CountDownLatch(CONCURRENCY);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<CacheResult<Payload>>> futures = new ArrayList<>();
        for (int i = 0; i < CONCURRENCY; i++) {
            int index = i;
            futures.add(drivers.submit(() -> {
                ready.countDown();
                go.await();
                return call.apply(index);
            }));
        }
        ready.await();
        go.countDown();

        List<Payload> results = new ArrayList<>();
        for (Future<CacheResult<Payload>> future : futures) {
            results.add(future.get().value());
        }
        return results;
    }

    private TwoTierCache newCache() {
        return new TwoTierCache(redisTemplate, new ObjectMapper(), new RedisLock(redisTemplate),
                Executors.newVirtualThreadPerTaskExecutor(), new SimpleMeterRegistry());
    }

    record Payload(String data) {
    }
}
