package com.truecost.provider.rental;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.truecost.provider.ProviderResult;
import com.truecost.provider.support.ResilientHttpClient;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contract test against a WireMock stand in for the unofficial RapidAPI car rental endpoint,
 * since the real upstream is fragile and unofficial per PLAN.md. Covers the four required
 * outcomes, a successful parse, a timeout, an HTTP 429, and a malformed response body, and
 * asserts every failure path returns Absent with a reason rather than throwing.
 */
class RapidApiRentalProviderTest {

    private WireMockServer wireMockServer;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(options().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    private RapidApiRentalProvider provider() {
        ResilientHttpClient httpClient = new ResilientHttpClient(CircuitBreakerRegistry.ofDefaults(), new SimpleMeterRegistry(), io.micrometer.observation.ObservationRegistry.NOOP);
        return new RapidApiRentalProvider(httpClient, "http://localhost:" + wireMockServer.port(),
                "test-key", "test-host", 1);
    }

    private RentalQuoteRequest request() {
        return new RentalQuoteRequest("JFK", LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 16), "MIDSIZE");
    }

    @Test
    void returnsParsedQuotesOnSuccess() {
        stubFor(get(urlPathEqualTo("/search")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"data":[
                          {"vendor":"Avis","vendor_code":"avis","car_class":"MIDSIZE","total_price":245.67,"rental_days":2},
                          {"vendor":"Hertz","vendor_code":"hertz","car_class":"MIDSIZE","total_price":260.10,"rental_days":2}
                        ]}
                        """)));

        ProviderResult<List<RentalQuote>> result = provider().fetchQuotes(request());

        List<RentalQuote> quotes = presentValue(result);
        assertThat(quotes).hasSize(2);
        assertThat(quotes).extracting(RentalQuote::companyCode).containsExactlyInAnyOrder("AVIS", "HERTZ");
        assertThat(quotes).allSatisfy(quote -> assertThat(quote.source()).isEqualTo("RAPIDAPI"));
    }

    @Test
    void returnsAbsentOnTimeout() {
        stubFor(get(urlPathEqualTo("/search")).willReturn(aResponse().withFixedDelay(3000).withStatus(200)));

        ProviderResult<List<RentalQuote>> result = provider().fetchQuotes(request());

        assertThat(result).isInstanceOf(ProviderResult.Absent.class);
    }

    @Test
    void returnsAbsentOnHttp429() {
        stubFor(get(urlPathEqualTo("/search")).willReturn(aResponse().withStatus(429)));

        ProviderResult<List<RentalQuote>> result = provider().fetchQuotes(request());

        assertThat(result).isInstanceOf(ProviderResult.Absent.class);
    }

    @Test
    void returnsAbsentOnMalformedResponseBody() {
        stubFor(get(urlPathEqualTo("/search")).willReturn(aResponse().withStatus(200).withBody("not json at all")));

        ProviderResult<List<RentalQuote>> result = provider().fetchQuotes(request());

        assertThat(result).isInstanceOf(ProviderResult.Absent.class);
    }

    @Test
    void returnsAbsentWhenDataArrayHasNoUsableEntries() {
        stubFor(get(urlPathEqualTo("/search")).willReturn(aResponse().withStatus(200)
                .withBody("""
                        {"data":[{"vendor":"Avis"}]}
                        """)));

        ProviderResult<List<RentalQuote>> result = provider().fetchQuotes(request());

        assertThat(result).isInstanceOf(ProviderResult.Absent.class);
    }

    private static List<RentalQuote> presentValue(ProviderResult<List<RentalQuote>> result) {
        return switch (result) {
            case ProviderResult.Present<List<RentalQuote>> present -> present.value();
            case ProviderResult.Absent<List<RentalQuote>> absent ->
                    throw new AssertionError("expected a present result, reason=" + absent.reason());
        };
    }
}
