package com.truecost.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * A two tier cache, Caffeine L1 backed by Redis L2, where L1's 30 second expiry is deliberately
 * shorter than every logical TTL so it never masks a stale transition, and Redis holds each
 * envelope at twice its logical TTL to give stale-while-revalidate a window to serve from. A stale
 * hit returns immediately with a non-blocking background refresh, and a full miss runs the keyed
 * singleflight, in-process coalescing plus a cross-instance Redis lock, so concurrent misses
 * across any number of instances collapse to exactly one upstream fetch.
 */
@Component
public class TwoTierCache {

    private static final Logger log = LoggerFactory.getLogger(TwoTierCache.class);

    private static final Duration L1_TTL = Duration.ofSeconds(30);
    private static final long L1_MAXIMUM_SIZE = 10_000;
    private static final Duration LOCK_TTL = Duration.ofMillis(5000);
    private static final Duration LOCK_POLL_INTERVAL = Duration.ofMillis(30);

    private final Cache<String, CacheEnvelope<?>> l1Cache;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RedisLock redisLock;
    private final Singleflight singleflight;
    private final Executor virtualThreadExecutor;
    private final MeterRegistry meterRegistry;

    public TwoTierCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, RedisLock redisLock,
            Executor virtualThreadExecutor, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.redisLock = redisLock;
        this.singleflight = new Singleflight();
        this.virtualThreadExecutor = virtualThreadExecutor;
        this.meterRegistry = meterRegistry;
        this.l1Cache = Caffeine.newBuilder()
                .expireAfterWrite(L1_TTL)
                .maximumSize(L1_MAXIMUM_SIZE)
                .build();
    }

    public <T> CacheResult<T> get(String key, Duration logicalTtl, TypeReference<T> typeRef, Supplier<T> loader) {
        String cacheClass = CacheKeys.classOf(key);
        CacheEnvelope<T> envelope = getFromL1(key);
        boolean fromL1 = envelope != null;
        if (envelope == null) {
            envelope = readFromRedis(key, typeRef);
            if (envelope != null) {
                l1Cache.put(key, envelope);
            }
        }

        Instant now = Instant.now();
        if (envelope != null && envelope.isFresh(now)) {
            recordRequest(cacheClass, fromL1 ? "l1_hit" : "l2_hit");
            return new CacheResult<>(envelope.value(), DataFreshness.FRESH);
        }
        if (envelope != null && envelope.isWithinStaleWindow(now, logicalTtl)) {
            recordRequest(cacheClass, "stale");
            meterRegistry.counter("truecost.cache.stale.served", "class", cacheClass).increment();
            triggerBackgroundRefresh(key, cacheClass, logicalTtl, typeRef, loader);
            return new CacheResult<>(envelope.value(), DataFreshness.STALE);
        }

        recordRequest(cacheClass, "miss");
        SingleflightResult<CacheEnvelope<T>> result = singleflight.load(key,
                () -> loadWithCrossInstanceLock(key, logicalTtl, typeRef, loader));
        if (result.coalesced()) {
            meterRegistry.counter("truecost.cache.singleflight.coalesced", "class", cacheClass).increment();
        }
        l1Cache.put(key, result.value());
        return new CacheResult<>(result.value().value(), DataFreshness.FRESH);
    }

    private <T> void triggerBackgroundRefresh(String key, String cacheClass, Duration logicalTtl,
            TypeReference<T> typeRef, Supplier<T> loader) {
        virtualThreadExecutor.execute(() -> {
            try {
                SingleflightResult<CacheEnvelope<T>> result = singleflight.load(key,
                        () -> loadWithCrossInstanceLock(key, logicalTtl, typeRef, loader));
                String outcome = result.coalesced() ? "skipped" : "leader";
                meterRegistry.counter("truecost.cache.refresh", "class", cacheClass, "outcome", outcome).increment();
                if (result.coalesced()) {
                    meterRegistry.counter("truecost.cache.singleflight.coalesced", "class", cacheClass).increment();
                } else {
                    l1Cache.put(key, result.value());
                    log.info("background refresh landed, key={} expiresAt={}", key, result.value().expiresAt());
                }
            } catch (RuntimeException e) {
                log.warn("background refresh failed, key={} reason={}", key, e.toString());
            }
        });
    }

    /**
     * The local singleflight leader's loader, run at most once per key across this instance. It
     * also acquires the cross instance Redis lock, so at most one instance among however many are
     * running actually calls the caller supplied loader for this key.
     */
    private <T> CacheEnvelope<T> loadWithCrossInstanceLock(String key, Duration logicalTtl,
            TypeReference<T> typeRef, Supplier<T> loader) {
        while (true) {
            Optional<String> token = redisLock.tryAcquire(key, LOCK_TTL);
            if (token.isPresent()) {
                try {
                    CacheEnvelope<T> existing = readFromRedis(key, typeRef);
                    if (existing != null && existing.isFresh(Instant.now())) {
                        return existing;
                    }
                    T value = loader.get();
                    CacheEnvelope<T> fresh = CacheEnvelope.freshlyFetched(value, Instant.now(), logicalTtl);
                    writeToRedis(key, fresh, logicalTtl);
                    log.info("singleflight leader fetched and cached, key={} expiresAt={}", key, fresh.expiresAt());
                    return fresh;
                } finally {
                    redisLock.release(key, token.get());
                }
            }

            Instant pollDeadline = Instant.now().plus(LOCK_TTL);
            CacheEnvelope<T> polled = pollForFreshValue(key, typeRef, pollDeadline);
            if (polled != null) {
                return polled;
            }
            log.warn("lock poll timed out waiting for another instance's fetch, key={}, retrying acquisition", key);
        }
    }

    private <T> CacheEnvelope<T> pollForFreshValue(String key, TypeReference<T> typeRef, Instant deadline) {
        while (Instant.now().isBefore(deadline)) {
            CacheEnvelope<T> envelope = readFromRedis(key, typeRef);
            if (envelope != null && envelope.isFresh(Instant.now())) {
                return envelope;
            }
            sleepQuietly(LOCK_POLL_INTERVAL);
        }
        return null;
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while polling for cache value", e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> CacheEnvelope<T> getFromL1(String key) {
        return (CacheEnvelope<T>) l1Cache.getIfPresent(key);
    }

    private <T> void writeToRedis(String key, CacheEnvelope<T> envelope, Duration logicalTtl) {
        ObjectNode node = objectMapper.createObjectNode();
        node.set("value", objectMapper.valueToTree(envelope.value()));
        node.put("fetchedAt", envelope.fetchedAt().toEpochMilli());
        node.put("expiresAt", envelope.expiresAt().toEpochMilli());
        try {
            String json = objectMapper.writeValueAsString(node);
            redisTemplate.opsForValue().set(key, json, logicalTtl.multipliedBy(2));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize cache envelope for key " + key, e);
        }
    }

    private <T> CacheEnvelope<T> readFromRedis(String key, TypeReference<T> typeRef) {
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            JavaType valueType = objectMapper.getTypeFactory().constructType(typeRef);
            T value = objectMapper.readerFor(valueType).readValue(node.get("value"));
            Instant fetchedAt = Instant.ofEpochMilli(node.get("fetchedAt").asLong());
            Instant expiresAt = Instant.ofEpochMilli(node.get("expiresAt").asLong());
            return new CacheEnvelope<>(value, fetchedAt, expiresAt);
        } catch (IOException e) {
            log.warn("failed to deserialize cache envelope, key={} reason={}", key, e.toString());
            return null;
        }
    }

    private void recordRequest(String cacheClass, String outcome) {
        meterRegistry.counter("truecost.cache.request", "class", cacheClass, "outcome", outcome).increment();
    }
}
