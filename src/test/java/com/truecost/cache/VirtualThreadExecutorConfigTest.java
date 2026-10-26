package com.truecost.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VirtualThreadExecutorConfigTest {

    private final ObservationRegistry registry = ObservationRegistry.create();
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        registry.observationConfig().observationHandler(context -> true);
        ObservationThreadLocalAccessor.getInstance().setObservationRegistry(registry);
        executor = new VirtualThreadExecutorConfig().virtualThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void taskSeesTheObservationOpenOnTheSubmittingThread() throws Exception {
        Observation parent = Observation.start("plan", registry);
        Observation seen;
        try (Observation.Scope ignored = parent.openScope()) {
            seen = executor.submit(registry::getCurrentObservation).get(5, TimeUnit.SECONDS);
        } finally {
            parent.stop();
        }

        assertThat(seen).isSameAs(parent);
    }

    @Test
    void childObservationInsideATaskNestsUnderTheCallersObservation() throws Exception {
        Observation parent = Observation.start("plan", registry);
        Observation.ContextView parentOfChild;
        try (Observation.Scope ignored = parent.openScope()) {
            parentOfChild = executor.submit(() -> {
                Observation child = Observation.start("osrm-route", registry);
                try {
                    return child.getContextView().getParentObservation().getContextView();
                } finally {
                    child.stop();
                }
            }).get(5, TimeUnit.SECONDS);
        } finally {
            parent.stop();
        }

        assertThat(parentOfChild).isSameAs(parent.getContextView());
    }

    @Test
    void taskSubmittedWithoutAnObservationSeesNone() throws Exception {
        Observation seen = executor.submit(registry::getCurrentObservation).get(5, TimeUnit.SECONDS);

        assertThat(seen).isNull();
    }
}
