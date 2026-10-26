package com.truecost.stream;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * The Kafka Streams prefetcher deployable, a separate process from the API that communicates with
 * the rest of the system only through Kafka topics, never a direct call. It deliberately excludes
 * com.truecost.api from its component scan, so it builds TripPlanner for cache warming without
 * standing up TripController or the synchronous web endpoint. It runs with the prefetcher profile so
 * application-prefetcher.yml gives it its own service name, which is what tells its traces and
 * metrics apart from the API in Tempo and Prometheus.
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
        new SpringApplicationBuilder(PrefetcherApplication.class).profiles("prefetcher").run(args);
    }
}
