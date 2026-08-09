package com.truecost.cache;

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
 */
@Configuration
public class VirtualThreadExecutorConfig {

    @Bean
    public ExecutorService virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
