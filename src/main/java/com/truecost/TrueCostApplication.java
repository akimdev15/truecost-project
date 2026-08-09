package com.truecost;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * The synchronous API deployable. The Kafka Streams prefetcher is a separate deployable with its
 * own main class, PrefetcherApplication, per PLAN.md's Phase 6 deployment topology, so the API and
 * the prefetcher scale and deploy independently and only ever communicate through Kafka topics.
 * This app therefore never component scans com.truecost.stream, the prefetcher only package, so it
 * never starts a topology or a Kafka Streams instance. The API still produces SearchRequested and
 * QuoteSnapshot events, that producer lives outside the stream package and is gated by
 * truecost.events.enabled.
 */
@SpringBootApplication
@ComponentScan(excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX, pattern = "com\\.truecost\\.stream\\..*"))
public class TrueCostApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrueCostApplication.class, args);
    }
}
