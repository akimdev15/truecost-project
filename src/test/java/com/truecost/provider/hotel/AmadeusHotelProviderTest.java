package com.truecost.provider.hotel;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
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
 * Contract test against a WireMock stand in for Amadeus Self-Service. Covers the OAuth2 client
 * credentials token exchange succeeding then the hotel search succeeding, a failed token
 * exchange, and the three required search failure outcomes, a timeout, an HTTP 429, and a
 * malformed body, all of which must return Absent rather than throw.
 */
class AmadeusHotelProviderTest {

    private static final String TOKEN_PATH = "/v1/security/oauth2/token";
    private static final String SEARCH_PATH = "/v2/shopping/hotel-offers";

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

    private AmadeusHotelProvider provider() {
        ResilientHttpClient httpClient = new ResilientHttpClient(CircuitBreakerRegistry.ofDefaults(), new SimpleMeterRegistry(), io.micrometer.observation.ObservationRegistry.NOOP);
        return new AmadeusHotelProvider(httpClient, "http://localhost:" + wireMockServer.port(),
                "test-client-id", "test-client-secret", 1);
    }

    private HotelRequest request() {
        return new HotelRequest("NYC", LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 16));
    }

    private void stubTokenSuccess() {
        stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(200)
                .withBody("""
                        {"type":"amadeusOAuth2Token","access_token":"test-access-token","expires_in":1799,"token_type":"Bearer"}
                        """)));
    }

    @Test
    void returnsOffersAfterAnOauthTokenExchangeAndSuccessfulSearch() {
        stubTokenSuccess();
        stubFor(get(urlPathEqualTo(SEARCH_PATH)).willReturn(aResponse().withStatus(200)
                .withBody("""
                        {"data":[{"hotel":{"name":"Hotel TrueCost"},"offers":[{"price":{"total":"245.00"}}]}]}
                        """)));

        ProviderResult<List<HotelOffer>> result = provider().fetchOffers(request());

        List<HotelOffer> offers = presentValue(result);
        assertThat(offers).hasSize(1);
        assertThat(offers.get(0).hotelName()).isEqualTo("Hotel TrueCost");
        assertThat(offers.get(0).nights()).isEqualTo(2);
        assertThat(offers.get(0).totalCost().cents()).isEqualTo(24500L);
        assertThat(offers.get(0).source()).isEqualTo("AMADEUS");
    }

    @Test
    void returnsAbsentWhenTokenExchangeFails() {
        stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(401)));

        ProviderResult<List<HotelOffer>> result = provider().fetchOffers(request());

        assertThat(result).isInstanceOf(ProviderResult.Absent.class);
    }

    @Test
    void returnsAbsentOnTimeout() {
        stubTokenSuccess();
        stubFor(get(urlPathEqualTo(SEARCH_PATH)).willReturn(aResponse().withFixedDelay(3000).withStatus(200)));

        ProviderResult<List<HotelOffer>> result = provider().fetchOffers(request());

        assertThat(result).isInstanceOf(ProviderResult.Absent.class);
    }

    @Test
    void returnsAbsentOnHttp429() {
        stubTokenSuccess();
        stubFor(get(urlPathEqualTo(SEARCH_PATH)).willReturn(aResponse().withStatus(429)));

        ProviderResult<List<HotelOffer>> result = provider().fetchOffers(request());

        assertThat(result).isInstanceOf(ProviderResult.Absent.class);
    }

    @Test
    void returnsAbsentOnMalformedResponseBody() {
        stubTokenSuccess();
        stubFor(get(urlPathEqualTo(SEARCH_PATH)).willReturn(aResponse().withStatus(200).withBody("not json")));

        ProviderResult<List<HotelOffer>> result = provider().fetchOffers(request());

        assertThat(result).isInstanceOf(ProviderResult.Absent.class);
    }

    private static List<HotelOffer> presentValue(ProviderResult<List<HotelOffer>> result) {
        return switch (result) {
            case ProviderResult.Present<List<HotelOffer>> present -> present.value();
            case ProviderResult.Absent<List<HotelOffer>> absent ->
                    throw new AssertionError("expected a present result, reason=" + absent.reason());
        };
    }
}
