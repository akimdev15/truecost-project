package com.truecost.stream.consumer;

import com.truecost.aggregate.TripPlanner;
import com.truecost.api.dto.TripPlanRequest;
import com.truecost.events.HotRouteSignal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the hot-routes topic and warms caches for a route the topology found in demand. It
 * calls TripPlanner.plan with userInitiated false and publishes no SearchRequested, so a cache
 * warm here cannot feed back into hot route detection. hasPersonalEzpass is hardcoded false
 * because that flag only steers the pure per-option strategy engine, never a cache key, so
 * warming with it false still populates exactly what any real searcher will hit.
 */
@Component
public class PrefetchConsumer {

    private static final Logger log = LoggerFactory.getLogger(PrefetchConsumer.class);

    private final TripPlanner tripPlanner;

    public PrefetchConsumer(TripPlanner tripPlanner) {
        this.tripPlanner = tripPlanner;
    }

    @KafkaListener(
            topics = "${truecost.kafka.topics.hot-routes.name}",
            groupId = "hot-route-prefetch")
    public void onHotRoute(HotRouteSignal signal) {
        if (signal.getPickupLocationCode() == null || signal.getDestinationCode() == null) {
            log.warn("hot route signal missing location codes, skipping prefetch, routeKey={}", signal.getRouteKey());
            return;
        }

        TripPlanRequest request = new TripPlanRequest(
                signal.getOriginLat(), signal.getOriginLng(), signal.getPickupLocationCode(),
                signal.getDestLat(), signal.getDestLng(), signal.getDestinationCode(),
                signal.getDepartureAt(), signal.getReturnAt(),
                false, signal.getCarClass(), List.of());

        tripPlanner.plan(request, false);
        log.info("prefetched hot route, routeKey={} searchCount={} windowEnd={}",
                signal.getRouteKey(), signal.getSearchCount(), signal.getWindowEnd());
    }
}
