package com.truecost.cache;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One shared virtual thread pool for both the aggregator's provider fan-out and the cache's
 * background stale-while-revalidate refresh, since virtual threads are cheap enough that a
 * dedicated pool per use buys nothing. It is application scoped, not request scoped, so a
 * background refresh submitted while handling one request keeps running after that request's
 * response is already sent.
 *
 * <p>Each task captures the submitting thread's context at submit time, which carries the current
 * observation, so spans opened inside a task nest under the plan request instead of starting a
 * new root trace. A background refresh therefore stays in the trace of the request that triggered
 * it, which is intended, the refresh is that request's work.
 */
@Configuration
public class VirtualThreadExecutorConfig {

    @Bean
    public ExecutorService virtualThreadExecutor() {
        return wrap(Executors.newVirtualThreadPerTaskExecutor());
    }

    static ExecutorService wrap(ExecutorService delegate) {
        return ContextExecutorService.wrap(delegate, ContextSnapshotFactory.builder().build());
    }
}
