package com.truecost.cache;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The in process half of the keyed singleflight, a plain future map rather than Caffeine's
 * AsyncLoadingCache because the leader here must additionally acquire a cross instance Redis lock,
 * coordination TwoTierCache needs visible rather than hidden inside a loader callback. The leader
 * removes its own key with remove(key, future), keyed on the exact future instance, so a later
 * call after this one completes starts a fresh load instead of reusing a finished future forever.
 */
public final class Singleflight {

    private final ConcurrentHashMap<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

    /**
     * Runs loader for key, coalescing concurrent callers onto one invocation. A loader exception
     * propagates to every coalesced caller, wrapped as CompletionException on followers but
     * rethrown as-is on the leader.
     */
    @SuppressWarnings("unchecked")
    public <T> SingleflightResult<T> load(String key, Supplier<T> loader) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        CompletableFuture<Object> existing = inFlight.putIfAbsent(key, future);

        if (existing != null) {
            return new SingleflightResult<>((T) existing.join(), true);
        }

        try {
            T value = loader.get();
            future.complete(value);
            return new SingleflightResult<>(value, false);
        } catch (RuntimeException | Error e) {
            future.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, future);
        }
    }

    /** True while some caller's load for key has not yet completed. Used to tag refresh metrics. */
    public boolean isInFlight(String key) {
        return inFlight.containsKey(key);
    }
}
