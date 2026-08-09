package com.truecost.provider.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.truecost.provider.ProviderResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the shared HTTP transport every real connector goes through: a single retry on
 * IOException or timeout, a non 200 status and a malformed body both collapsing into Absent
 * rather than an exception, and a resilience4j circuit breaker that opens after repeated
 * failures and keeps returning Absent, never throwing or blocking, while open.
 */
class ResilientHttpClientTest {

    private WireMockServer wireMockServer;
    private CircuitBreakerRegistry circuitBreakerRegistry;
    private ResilientHttpClient client;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(options().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build();
        circuitBreakerRegistry = CircuitBreakerRegistry.of(config);
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        client = new ResilientHttpClient(circuitBreakerRegistry, meterRegistry, io.micrometer.observation.ObservationRegistry.NOOP);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void returnsPresentOnSuccessfulResponse() {
        stubFor(get(urlEqualTo("/ping")).willReturn(aResponse().withStatus(200).withBody("hello")));

        ProviderResult<String> result = client.get("ping-provider", uri("/ping"), Duration.ofSeconds(2), body -> body);

        assertThat(result).isEqualTo(ProviderResult.present("hello"));
    }

    @Test
    void returnsAbsentOnNonOkStatusWithoutThrowing() {
        stubFor(get(urlEqualTo("/rate-limited")).willReturn(aResponse().withStatus(429)));

        ProviderResult<String> result = client.get("rate-limited-provider", uri("/rate-limited"), Duration.ofSeconds(2), body -> body);

        assertThat(result).isInstanceOf(ProviderResult.Absent.class);
    }

    @Test
    void returnsAbsentWhenTheBodyParserThrowsOnAMalformedBody() {
        stubFor(get(urlEqualTo("/malformed")).willReturn(aResponse().withStatus(200).withBody("not the expected shape")));

        ProviderResult<Integer> result = client.get("malformed-provider", uri("/malformed"), Duration.ofSeconds(2),
                body -> {
                    throw new IllegalStateException("unexpected response shape");
                });

        assertThat(result).isInstanceOf(ProviderResult.Absent.class);
    }

    @Test
    void retriesOnceOnConnectionFailureThenSucceeds() {
        stubFor(get(urlEqualTo("/flaky")).inScenario("flaky-upstream")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/flaky")).inScenario("flaky-upstream")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200).withBody("recovered-body")));

        ProviderResult<String> result = client.get("flaky-provider", uri("/flaky"), Duration.ofSeconds(2), body -> body);

        assertThat(result).isEqualTo(ProviderResult.present("recovered-body"));
    }

    @Test
    void circuitBreakerOpensAfterRepeatedFailuresAndAbsentIsReturnedWhileOpen() {
        stubFor(get(urlEqualTo("/always-down")).willReturn(aResponse().withStatus(500)));
        String providerName = "always-down-provider";

        for (int i = 0; i < 4; i++) {
            ProviderResult<String> result = client.get(providerName, uri("/always-down"), Duration.ofSeconds(2), body -> body);
            assertThat(result).isInstanceOf(ProviderResult.Absent.class);
        }

        assertThat(circuitBreakerRegistry.circuitBreaker(providerName).getState()).isEqualTo(CircuitBreaker.State.OPEN);

        ProviderResult<String> whileOpen = client.get(providerName, uri("/always-down"), Duration.ofSeconds(2), body -> body);
        assertThat(whileOpen).isInstanceOf(ProviderResult.Absent.class);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + wireMockServer.port() + path);
    }
}
