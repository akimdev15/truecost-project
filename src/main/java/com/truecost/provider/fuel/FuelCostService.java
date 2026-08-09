package com.truecost.provider.fuel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.truecost.cache.CacheKeys;
import com.truecost.cache.CacheResult;
import com.truecost.cache.DataFreshness;
import com.truecost.cache.TwoTierCache;
import com.truecost.domain.Money;
import com.truecost.persist.VehicleClassRepository;
import com.truecost.provider.ProviderResult;
import com.truecost.provider.support.ResilientHttpClient;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Estimates a trip's fuel cost from distance and vehicle class. The regional gas price is fetched
 * through the shared TwoTierCache rather than private per instance state, so the one upstream
 * fetch guarantee holds across instances, falling back to a static price when the EIA connector is
 * disabled or the fetch fails.
 */
@Component
public class FuelCostService {

    private static final Logger log = LoggerFactory.getLogger(FuelCostService.class);
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    private final VehicleClassRepository vehicleClassRepository;
    private final ResilientHttpClient httpClient;
    private final TwoTierCache twoTierCache;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String apiKey;
    private final String baseUrl;
    private final String region;
    private final Duration timeout;
    private final double fallbackPricePerGallon;

    public FuelCostService(
            VehicleClassRepository vehicleClassRepository,
            ResilientHttpClient httpClient,
            TwoTierCache twoTierCache,
            @Value("${truecost.providers.fuel.eia.enabled:false}") boolean enabled,
            @Value("${truecost.providers.fuel.eia.api-key:}") String apiKey,
            @Value("${truecost.providers.fuel.eia.base-url:https://api.eia.gov/v2/petroleum/pri/gnd/data/}") String baseUrl,
            @Value("${truecost.providers.fuel.eia.region:Y35NY}") String region,
            @Value("${truecost.providers.fuel.eia.timeout-seconds:2}") long timeoutSeconds,
            @Value("${truecost.providers.fuel.fallback-price-per-gallon:3.50}") double fallbackPricePerGallon) {
        this.vehicleClassRepository = vehicleClassRepository;
        this.httpClient = httpClient;
        this.twoTierCache = twoTierCache;
        this.objectMapper = new ObjectMapper();
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.region = region;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.fallbackPricePerGallon = fallbackPricePerGallon;
    }

    /**
     * Resolves the regional gas price through the shared cache. Callers needing more than one
     * FuelEstimate for the same trip should call this once and reuse the result with the pure
     * estimateFuelCost overload below, rather than resolving the price again per class.
     */
    public FuelPriceResolution resolveFuelPrice() {
        if (!enabled) {
            return new FuelPriceResolution(fallbackPricePerGallon, true, DataFreshness.UNAVAILABLE);
        }

        String key = CacheKeys.fuel(region);
        try {
            CacheResult<FuelPriceValue> result = twoTierCache.get(key, CACHE_TTL,
                    new TypeReference<FuelPriceValue>() {
                    }, this::fetchLiveEiaPrice);
            return new FuelPriceResolution(result.value().pricePerGallon(), false, result.freshness());
        } catch (RuntimeException e) {
            log.warn("eia gas price fetch failed after cache and lock retries, falling back to static price, reason={}",
                    e.toString());
            return new FuelPriceResolution(fallbackPricePerGallon, true, DataFreshness.UNAVAILABLE);
        }
    }

    /** Pure computation of distance, mpg, an already resolved price, and its fallback flag, no I/O. */
    public FuelEstimate estimateFuelCost(double distanceMiles, String vehicleClassCode, FuelPriceResolution resolution) {
        double mpg = vehicleClassRepository.findDefaultMpg(vehicleClassCode);
        double gallons = distanceMiles / mpg;
        long costCents = Math.round(gallons * resolution.pricePerGallon() * 100);
        return new FuelEstimate(Money.ofCents(costCents), resolution.pricePerGallon(), mpg, resolution.isFallback());
    }

    /** Convenience overload that resolves the price itself, for callers that need only one estimate. */
    public FuelEstimate estimateFuelCost(double distanceMiles, String vehicleClassCode) {
        return estimateFuelCost(distanceMiles, vehicleClassCode, resolveFuelPrice());
    }

    private FuelPriceValue fetchLiveEiaPrice() {
        ProviderResult<Double> result = httpClient.get("EIA_FUEL", buildUri(), timeout, this::parsePrice);
        return switch (result) {
            case ProviderResult.Present<Double> present -> {
                log.info("eia gas price refreshed, pricePerGallon={}", present.value());
                yield new FuelPriceValue(present.value(), false);
            }
            case ProviderResult.Absent<Double> absent ->
                    throw new IllegalStateException("EIA gas price fetch failed, reason=" + absent.reason());
        };
    }

    private URI buildUri() {
        return UriComponentsBuilder.fromHttpUrl(baseUrl)
                .queryParam("api_key", apiKey)
                .queryParam("frequency", "weekly")
                .queryParam("data[0]", "value")
                .queryParam("facets[duoarea][]", region)
                .queryParam("sort[0][column]", "period")
                .queryParam("sort[0][direction]", "desc")
                .queryParam("length", 1)
                .build()
                .encode()
                .toUri();
    }

    private double parsePrice(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("could not parse EIA response as JSON", e);
        }

        JsonNode valueNode = root.path("response").path("data").path(0).path("value");
        if (valueNode.isMissingNode() || valueNode.isNull()) {
            throw new IllegalStateException("EIA response missing response.data[0].value");
        }

        try {
            return Double.parseDouble(valueNode.asText());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("EIA response value was not numeric, value=" + valueNode.asText());
        }
    }
}
