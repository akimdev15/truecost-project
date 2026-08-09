package com.truecost.provider.fuel;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.truecost.cache.CacheResult;
import com.truecost.cache.DataFreshness;
import com.truecost.cache.TwoTierCache;
import com.truecost.persist.VehicleClassRepository;
import com.truecost.provider.support.ResilientHttpClient;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Contract test against a WireMock stand in for the EIA open data API. Covers a successful live
 * price fetch, a disabled connector, and the three required failure outcomes, a timeout, an
 * HTTP 429, and a malformed body, all of which must fall back to the static price per gallon
 * rather than let an exception reach the caller.
 */
@ExtendWith(MockitoExtension.class)
class FuelCostServiceTest {

    private static final String EIA_PATH = "/v2/petroleum/pri/gnd/data/";

    private WireMockServer wireMockServer;

    @Mock
    private VehicleClassRepository vehicleClassRepository;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(options().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());
        lenient().when(vehicleClassRepository.findDefaultMpg("MIDSIZE")).thenReturn(30.0);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    private FuelCostService service(boolean enabled) {
        ResilientHttpClient httpClient = new ResilientHttpClient(CircuitBreakerRegistry.ofDefaults(), new SimpleMeterRegistry(), io.micrometer.observation.ObservationRegistry.NOOP);
        return new FuelCostService(vehicleClassRepository, httpClient, passthroughCache(), enabled, "test-key",
                "http://localhost:" + wireMockServer.port() + EIA_PATH, "Y35NY", 1, 3.25);
    }

    /**
     * A pass through TwoTierCache that runs the loader directly on every get and never touches
     * Redis or Caffeine, so this unit test exercises FuelCostService's live fetch and fallback
     * behavior in isolation. A loader that throws surfaces straight to resolveFuelPrice, which is
     * exactly the failure path the fallback tests assert. The two tier cache's own coalescing,
     * stale while revalidate, and cross instance lock are covered by the cache's dedicated tests.
     */
    private TwoTierCache passthroughCache() {
        return new TwoTierCache(null, null, null, null, new SimpleMeterRegistry()) {
            @Override
            public <T> CacheResult<T> get(String key, Duration logicalTtl, TypeReference<T> typeRef, Supplier<T> loader) {
                return new CacheResult<>(loader.get(), DataFreshness.FRESH);
            }
        };
    }

    @Test
    void usesLiveEiaPriceOnSuccess() {
        stubFor(get(urlPathEqualTo(EIA_PATH)).willReturn(aResponse().withStatus(200)
                .withBody("""
                        {"response":{"data":[{"period":"2026-07-14","value":"3.412"}]}}
                        """)));

        FuelEstimate estimate = service(true).estimateFuelCost(300.0, "MIDSIZE");

        assertThat(estimate.priceIsFallback()).isFalse();
        assertThat(estimate.pricePerGallonUsed()).isEqualTo(3.412);
        assertThat(estimate.mpgUsed()).isEqualTo(30.0);
        assertThat(estimate.cost().cents()).isEqualTo(Math.round(300.0 / 30.0 * 3.412 * 100));
    }

    @Test
    void fallsBackToStaticPriceWhenConnectorDisabled() {
        FuelEstimate estimate = service(false).estimateFuelCost(300.0, "MIDSIZE");

        assertThat(estimate.priceIsFallback()).isTrue();
        assertThat(estimate.pricePerGallonUsed()).isEqualTo(3.25);
    }

    @Test
    void fallsBackToStaticPriceOnTimeout() {
        stubFor(get(urlPathEqualTo(EIA_PATH)).willReturn(aResponse().withFixedDelay(3000).withStatus(200)));

        FuelEstimate estimate = service(true).estimateFuelCost(300.0, "MIDSIZE");

        assertThat(estimate.priceIsFallback()).isTrue();
        assertThat(estimate.pricePerGallonUsed()).isEqualTo(3.25);
    }

    @Test
    void fallsBackToStaticPriceOnHttp429() {
        stubFor(get(urlPathEqualTo(EIA_PATH)).willReturn(aResponse().withStatus(429)));

        FuelEstimate estimate = service(true).estimateFuelCost(300.0, "MIDSIZE");

        assertThat(estimate.priceIsFallback()).isTrue();
    }

    @Test
    void fallsBackToStaticPriceOnMalformedBody() {
        stubFor(get(urlPathEqualTo(EIA_PATH)).willReturn(aResponse().withStatus(200).withBody("not json")));

        FuelEstimate estimate = service(true).estimateFuelCost(300.0, "MIDSIZE");

        assertThat(estimate.priceIsFallback()).isTrue();
    }
}
