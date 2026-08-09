package com.truecost.provider.rental;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.truecost.domain.Money;
import com.truecost.provider.ProviderResult;
import com.truecost.provider.support.ResilientHttpClient;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Real rental quote connector against an unofficial RapidAPI listing endpoint, disabled by
 * default. The upstream JSON shape is unofficial and fragile, so parsing skips individual entries
 * missing a required field rather than failing the whole batch, only throwing when no entries are
 * usable at all.
 */
@Component
@ConditionalOnProperty(prefix = "truecost.providers.rental.rapidapi", name = "enabled", havingValue = "true")
public class RapidApiRentalProvider implements RentalQuoteProvider {

    private static final Logger log = LoggerFactory.getLogger(RapidApiRentalProvider.class);

    private final ResilientHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final String apiHost;
    private final Duration timeout;

    public RapidApiRentalProvider(
            ResilientHttpClient httpClient,
            @Value("${truecost.providers.rental.rapidapi.base-url:https://car-rental-prices.p.rapidapi.com}") String baseUrl,
            @Value("${truecost.providers.rental.rapidapi.api-key:}") String apiKey,
            @Value("${truecost.providers.rental.rapidapi.api-host:car-rental-prices.p.rapidapi.com}") String apiHost,
            @Value("${truecost.providers.rental.rapidapi.timeout-seconds:2}") long timeoutSeconds) {
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.apiHost = apiHost;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    @Override
    public String name() {
        return "RAPIDAPI";
    }

    @Override
    public ProviderResult<List<RentalQuote>> fetchQuotes(RentalQuoteRequest request) {
        URI uri = buildUri(request);
        Map<String, String> headers = Map.of(
                "X-RapidAPI-Key", apiKey,
                "X-RapidAPI-Host", apiHost);
        return httpClient.get(name(), uri, timeout, headers, this::parseQuotes);
    }

    private URI buildUri(RentalQuoteRequest request) {
        return UriComponentsBuilder.fromHttpUrl(baseUrl + "/search")
                .queryParam("pickup_location", request.pickupLocationCode())
                .queryParam("pickup_date", request.pickupDate().toString())
                .queryParam("return_date", request.returnDate().toString())
                .queryParam("car_class", request.carClassCode())
                .build()
                .encode()
                .toUri();
    }

    private List<RentalQuote> parseQuotes(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("could not parse RapidAPI response as JSON", e);
        }

        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("RapidAPI response had no data array");
        }

        List<RentalQuote> quotes = new ArrayList<>();
        for (JsonNode entry : data) {
            String companyCode = entry.path("vendor_code").asText(null);
            String companyName = entry.path("vendor").asText(null);
            String carClassCode = entry.path("car_class").asText(null);
            JsonNode priceNode = entry.path("total_price");
            int rentalDays = entry.path("rental_days").asInt(-1);

            if (companyCode == null || companyName == null || carClassCode == null
                    || priceNode.isMissingNode() || !priceNode.isNumber() || rentalDays <= 0) {
                log.warn("skipping rapidapi rental entry with missing or invalid fields");
                continue;
            }

            long totalCents = Math.round(priceNode.asDouble() * 100);
            quotes.add(new RentalQuote(companyCode.toUpperCase(Locale.ROOT), companyName, carClassCode,
                    Money.ofCents(totalCents), rentalDays, name()));
        }

        if (quotes.isEmpty()) {
            throw new IllegalStateException("no usable rental quotes parsed from RapidAPI response");
        }
        return quotes;
    }
}
