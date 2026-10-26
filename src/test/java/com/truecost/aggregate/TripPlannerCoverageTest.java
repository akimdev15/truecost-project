package com.truecost.aggregate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.truecost.api.OutOfCoverageException;
import com.truecost.api.dto.TripPlanRequest;
import com.truecost.cache.CacheResult;
import com.truecost.cache.DataFreshness;
import com.truecost.cache.TwoTierCache;
import com.truecost.coverage.CoverageRegion;
import com.truecost.coverage.CoverageRegions;
import com.truecost.events.EventPublisher;
import com.truecost.persist.TollProgramRepository;
import com.truecost.persist.VehicleClassRepository;
import com.truecost.provider.fuel.FuelCostService;
import com.truecost.route.Route;
import com.truecost.route.RouteClient;
import com.truecost.route.RoutePoint;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TripPlannerCoverageTest {

    private static final Instant DEPART = Instant.parse("2026-08-07T22:00:00Z");
    private static final Instant RETURN = Instant.parse("2026-08-09T18:00:00Z");

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final RouteClient routeClient = mock(RouteClient.class);
    private final EventPublisher eventPublisher = mock(EventPublisher.class);
    private TripPlanner planner;

    @BeforeEach
    void setUp() {
        TwoTierCache cache = mock(TwoTierCache.class);
        when(cache.get(anyString(), any(), any(), any())).thenAnswer(invocation -> {
            Supplier<?> loader = invocation.getArgument(3);
            return new CacheResult<>(loader.get(), DataFreshness.FRESH);
        });
        VehicleClassRepository vehicleClasses = mock(VehicleClassRepository.class);
        when(vehicleClasses.findTollClass("MIDSIZE")).thenReturn("PASSENGER");
        when(vehicleClasses.findRepresentativeCodeForTollClass("PASSENGER")).thenReturn("MIDSIZE");

        CoverageRegions regions = CoverageRegions.of(List.of(
                new CoverageRegion("EAST", "East", 39.75, -76.00, 41.20, -71.75)));

        planner = new TripPlanner(cache, routeClient, mock(LegPricer.class), mock(FuelCostService.class),
                vehicleClasses, mock(TollProgramRepository.class), List.of(), List.of(), executor,
                eventPublisher, new SimpleMeterRegistry(), regions, mock(RateSnapshotResolver.class));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void destinationOutsideCoverageIsRejectedBeforeAnySearchEventOrRouting() {
        TripPlanRequest boston = request(40.7505, -73.9934, 42.3601, -71.0589);

        assertThatThrownBy(() -> planner.plan(boston))
                .isInstanceOf(OutOfCoverageException.class)
                .hasMessageContaining("The destination is at (42.3601, -71.0589)");

        verifyNoInteractions(routeClient);
        verify(eventPublisher, never()).publishSearch(anyString(), anyString(), any());
    }

    @Test
    void originOutsideCoverageIsRejected() {
        TripPlanRequest request = request(42.3601, -71.0589, 40.7505, -73.9934);

        assertThatThrownBy(() -> planner.plan(request))
                .isInstanceOf(OutOfCoverageException.class)
                .hasMessageContaining("The origin is at");
    }

    @Test
    void routeThatLeavesCoverageIsRejectedEvenWhenBothEndpointsAreCovered() {
        Route detour = new Route(List.of(
                new RoutePoint(40.7505, -73.9934, 0),
                new RoutePoint(42.0000, -70.0000, 1800),
                new RoutePoint(39.9500, -75.1600, 3600)), 150_000, 3600);
        when(routeClient.fetchRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(detour);

        assertThatThrownBy(() -> planner.plan(request(40.7505, -73.9934, 39.9500, -75.1600)))
                .isInstanceOf(OutOfCoverageException.class)
                .hasMessageContaining("route passes through (42.0000, -70.0000)");
    }

    private static TripPlanRequest request(double oLat, double oLng, double dLat, double dLng) {
        return new TripPlanRequest(oLat, oLng, "EWR", dLat, dLng, "DST", DEPART, RETURN, false, "MIDSIZE", null);
    }
}
