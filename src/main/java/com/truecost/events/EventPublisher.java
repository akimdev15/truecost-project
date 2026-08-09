package com.truecost.events;

import com.truecost.api.dto.TripPlanRequest;
import com.truecost.provider.rental.RentalQuote;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes the SearchRequested and QuoteSnapshot Avro events, the only place in the API that
 * touches Kafka. Sends are fire and forget, a failure is logged and never propagated, so a Kafka
 * outage never fails a plan request.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final boolean enabled;
    private final String tripSearchesTopic;
    private final String quoteSnapshotsTopic;

    public EventPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${truecost.events.enabled:false}") boolean enabled,
            @Value("${truecost.kafka.topics.trip-searches.name}") String tripSearchesTopic,
            @Value("${truecost.kafka.topics.quote-snapshots.name}") String quoteSnapshotsTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.enabled = enabled;
        this.tripSearchesTopic = tripSearchesTopic;
        this.quoteSnapshotsTopic = quoteSnapshotsTopic;
    }

    public void publishSearch(String routeKey, String dateBucket, TripPlanRequest request) {
        if (!enabled) {
            return;
        }
        SearchRequested event = SearchRequested.newBuilder()
                .setEventId(UUID.randomUUID())
                .setRouteKey(routeKey)
                .setDateBucket(dateBucket)
                .setOriginLat(request.originLat())
                .setOriginLng(request.originLng())
                .setDestLat(request.destLat())
                .setDestLng(request.destLng())
                .setDepartureAt(request.departureAt())
                .setReturnAt(request.returnAt())
                .setHasPersonalEzpass(request.hasPersonalEzpass())
                .setCarClass(request.carClass())
                .setPickupLocationCode(request.pickupLocationCode())
                .setDestinationCode(request.destinationCode())
                .setOccurredAt(Instant.now())
                .build();
        send(tripSearchesTopic, routeKey, event);
    }

    public void publishQuoteSnapshot(String routeKey, TripPlanRequest request, RentalQuote quote,
            int rentalDays, boolean stale) {
        if (!enabled) {
            return;
        }
        long totalCents = quote.totalCost().cents();
        QuoteSnapshot snapshot = QuoteSnapshot.newBuilder()
                .setSnapshotId(UUID.randomUUID())
                .setRouteKey(routeKey)
                .setOriginLat(request.originLat())
                .setOriginLng(request.originLng())
                .setDestLat(request.destLat())
                .setDestLng(request.destLng())
                .setCompany(quote.companyCode())
                .setCarClass(quote.carClassCode())
                .setProvider(quote.source())
                .setBaseRateCents(totalCents)
                .setTaxesAndFeesCents(0L)
                .setTotalRentalCents(totalCents)
                .setPickupAt(request.departureAt())
                .setReturnAt(request.returnAt())
                .setRentalDays(rentalDays)
                .setStale(stale)
                .setFetchedAt(Instant.now())
                .build();
        send(quoteSnapshotsTopic, routeKey, snapshot);
    }

    private void send(String topic, String key, Object value) {
        kafkaTemplate.send(topic, key, value).whenComplete((result, error) -> {
            if (error != null) {
                log.warn("event publish failed, topic={} key={} reason={}", topic, key, error.toString());
            }
        });
    }
}
