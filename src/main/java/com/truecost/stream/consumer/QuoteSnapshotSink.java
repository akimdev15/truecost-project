package com.truecost.stream.consumer;

import com.truecost.events.QuoteSnapshot;
import com.truecost.persist.QuoteSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the quote-snapshots topic and writes each fetched rental quote into Postgres. Routing
 * snapshots through a topic rather than inserting inline on the request path keeps Postgres
 * writes off the request hot path, buffers write bursts, and captures quotes uniformly whether
 * fetched by a user request or the prefetch consumer.
 */
@Component
public class QuoteSnapshotSink {

    private static final Logger log = LoggerFactory.getLogger(QuoteSnapshotSink.class);

    private final QuoteSnapshotRepository repository;

    public QuoteSnapshotSink(QuoteSnapshotRepository repository) {
        this.repository = repository;
    }

    @KafkaListener(
            topics = "${truecost.kafka.topics.quote-snapshots.name}",
            groupId = "quote-snapshot-sink")
    public void onSnapshot(QuoteSnapshot snapshot) {
        repository.insert(snapshot);
        log.debug("quote snapshot persisted, routeKey={} company={} carClass={}",
                snapshot.getRouteKey(), snapshot.getCompany(), snapshot.getCarClass());
    }
}
