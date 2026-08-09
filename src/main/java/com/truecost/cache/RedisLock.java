package com.truecost.cache;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * The cross instance half of the keyed singleflight. release is a Lua compare-and-delete keyed on
 * a per acquisition token, so a holder that outlives the lock TTL can never delete a lock a new
 * leader has since acquired.
 */
@Component
public class RedisLock {

    private static final String KEY_PREFIX = "lock:";

    private static final RedisScript<Long> COMPARE_AND_DELETE = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('del', KEYS[1])
            else
              return 0
            end
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final String instanceId;

    public RedisLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.instanceId = UUID.randomUUID().toString();
    }

    /** Returns the acquisition token to pass to release when the lock was acquired, empty otherwise. */
    public java.util.Optional<String> tryAcquire(String cacheKey, Duration lockTtl) {
        String token = instanceId + ":" + UUID.randomUUID();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + cacheKey, token, lockTtl);
        return Boolean.TRUE.equals(acquired) ? java.util.Optional.of(token) : java.util.Optional.empty();
    }

    public void release(String cacheKey, String token) {
        redisTemplate.execute(COMPARE_AND_DELETE, List.of(KEY_PREFIX + cacheKey), token);
    }
}
