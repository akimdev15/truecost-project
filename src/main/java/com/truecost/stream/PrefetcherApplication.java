package com.truecost.stream;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Kafka Streams prefetcher deployable, a separate process from the API that communicates with
 * the rest of the system only through Kafka topics, never a direct call. It deliberately excludes
 * com.truecost.api from its component scan, so it builds TripPlanner for cache warming without
 * standing up TripController or the synchronous web endpoint.
 */
@SpringBootApplication(scanBasePackages = {
        "com.truecost.stream",
        "com.truecost.aggregate",
        "com.truecost.cache",
        "com.truecost.persist",
        "com.truecost.provider",
        "com.truecost.route",
        "com.truecost.toll",
        "com.truecost.events"})
public class PrefetcherApplication {

    public static void main(String[] args) {
        SpringApplication.run(PrefetcherApplication.class, args);
    }
}
