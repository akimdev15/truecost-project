package com.truecost.provider.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.truecost.domain.Money;
import com.truecost.provider.ProviderResult;
import com.truecost.provider.support.ResilientHttpClient;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Real hotel connector against the Amadeus Self-Service Hotel Search API, disabled by default.
 * Both the token exchange and the hotel search go through ResilientHttpClient under this
 * provider's own name, so one circuit breaker tracks Amadeus health across both calls.
 */
@Component
@ConditionalOnProperty(prefix = "truecost.providers.hotel.amadeus", name = "enabled", havingValue = "true")
public class AmadeusHotelProvider implements HotelProvider {

    private static final Logger log = LoggerFactory.getLogger(AmadeusHotelProvider.class);
    private static final Duration TOKEN_REFRESH_BUFFER = Duration.ofSeconds(60);

    private final ResilientHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String clientId;
    private final String clientSecret;
    private final Duration timeout;

    private final AtomicReference<CachedToken> tokenCache = new AtomicReference<>();

    public AmadeusHotelProvider(
            ResilientHttpClient httpClient,
            @Value("${truecost.providers.hotel.amadeus.base-url:https://test.api.amadeus.com}") String baseUrl,
            @Value("${truecost.providers.hotel.amadeus.client-id:}") String clientId,
            @Value("${truecost.providers.hotel.amadeus.client-secret:}") String clientSecret,
            @Value("${truecost.providers.hotel.amadeus.timeout-seconds:2}") long timeoutSeconds) {
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
        this.baseUrl = baseUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    @Override
    public String name() {
        return "AMADEUS";
    }

    @Override
    public ProviderResult<List<HotelOffer>> fetchOffers(HotelRequest request) {
        ProviderResult<String> tokenResult = resolveAccessToken();
        return switch (tokenResult) {
            case ProviderResult.Absent<String> absent -> ProviderResult.absent(absent.reason());
            case ProviderResult.Present<String> present -> searchOffers(request, present.value());
        };
    }

    private ProviderResult<List<HotelOffer>> searchOffers(HotelRequest request, String accessToken) {
        int nights = (int) ChronoUnit.DAYS.between(request.checkIn(), request.checkOut());
        if (nights <= 0) {
            return ProviderResult.absent("checkout date must be after checkin date");
        }

        URI uri = buildSearchUri(request);
        Map<String, String> headers = Map.of("Authorization", "Bearer " + accessToken);
        return httpClient.get(name(), uri, timeout, headers, body -> parseOffers(body, nights));
    }

    private ProviderResult<String> resolveAccessToken() {
        Instant now = Instant.now();
        CachedToken cached = tokenCache.get();
        if (cached != null && cached.expiresAt().minus(TOKEN_REFRESH_BUFFER).isAfter(now)) {
            return ProviderResult.present(cached.accessToken());
        }

        URI tokenUri = URI.create(baseUrl + "/v1/security/oauth2/token");
        String form = "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
        Map<String, String> headers = Map.of("Content-Type", "application/x-www-form-urlencoded");

        ProviderResult<CachedToken> result = httpClient.post(name(), tokenUri, timeout, headers, form, this::parseToken);
        return switch (result) {
            case ProviderResult.Present<CachedToken> present -> {
                tokenCache.set(present.value());
                yield ProviderResult.present(present.value().accessToken());
            }
            case ProviderResult.Absent<CachedToken> absent -> ProviderResult.absent(absent.reason());
        };
    }

    private CachedToken parseToken(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("could not parse Amadeus token response as JSON", e);
        }

        String accessToken = root.path("access_token").asText(null);
        int expiresIn = root.path("expires_in").asInt(-1);
        if (accessToken == null || expiresIn <= 0) {
            throw new IllegalStateException("Amadeus token response missing access_token or expires_in");
        }
        return new CachedToken(accessToken, Instant.now().plusSeconds(expiresIn));
    }

    private URI buildSearchUri(HotelRequest request) {
        return UriComponentsBuilder.fromHttpUrl(baseUrl + "/v2/shopping/hotel-offers")
                .queryParam("cityCode", request.destinationCode())
                .queryParam("checkInDate", request.checkIn().toString())
                .queryParam("checkOutDate", request.checkOut().toString())
                .queryParam("adults", 1)
                .build()
                .encode()
                .toUri();
    }

    private List<HotelOffer> parseOffers(String body, int nights) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("could not parse Amadeus hotel offers response as JSON", e);
        }

        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("Amadeus response had no hotel offers in data array");
        }

        List<HotelOffer> offers = new ArrayList<>();
        for (JsonNode entry : data) {
            String hotelName = entry.path("hotel").path("name").asText(null);
            JsonNode priceNode = entry.path("offers").path(0).path("price").path("total");
            if (hotelName == null || priceNode.isMissingNode() || !priceNode.isTextual()) {
                log.warn("skipping amadeus hotel entry with missing name or price");
                continue;
            }
            try {
                long totalCents = Math.round(Double.parseDouble(priceNode.asText()) * 100);
                offers.add(new HotelOffer(hotelName, Money.ofCents(totalCents), nights, name()));
            } catch (NumberFormatException e) {
                log.warn("skipping amadeus hotel entry with unparseable price, value={}", priceNode.asText());
            }
        }

        if (offers.isEmpty()) {
            throw new IllegalStateException("no usable hotel offers parsed from Amadeus response");
        }
        return offers;
    }

    private record CachedToken(String accessToken, Instant expiresAt) {
    }
}
