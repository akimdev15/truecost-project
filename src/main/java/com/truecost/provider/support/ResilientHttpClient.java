package com.truecost.provider.support;

import com.truecost.provider.ProviderResult;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Shared HTTP transport for every real external connector, with one retry on IOException or
 * timeout and a resilience4j circuit breaker keyed by provider name so each connector's health is
 * tracked independently. No exception ever crosses this class's boundary, every failure path
 * collapses into ProviderResult.Absent with a reason string.
 */
@Component
public class ResilientHttpClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientHttpClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    private final HttpClient httpClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final MeterRegistry meterRegistry;
    private final ObservationRegistry observationRegistry;

    public ResilientHttpClient(CircuitBreakerRegistry circuitBreakerRegistry, MeterRegistry meterRegistry,
            ObservationRegistry observationRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.meterRegistry = meterRegistry;
        this.observationRegistry = observationRegistry;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    public <T> ProviderResult<T> get(String providerName, URI uri, Duration timeout, Function<String, T> bodyParser) {
        return get(providerName, uri, timeout, Map.of(), bodyParser);
    }

    public <T> ProviderResult<T> get(String providerName, URI uri, Duration timeout, Map<String, String> headers,
            Function<String, T> bodyParser) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout).GET();
        headers.forEach(builder::header);
        return execute(providerName, builder.build(), bodyParser);
    }

    public <T> ProviderResult<T> post(String providerName, URI uri, Duration timeout, Map<String, String> headers,
            String requestBody, Function<String, T> bodyParser) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));
        headers.forEach(builder::header);
        return execute(providerName, builder.build(), bodyParser);
    }

    private <T> ProviderResult<T> execute(String providerName, HttpRequest request, Function<String, T> bodyParser) {
        return Observation.createNotStarted("provider.call", observationRegistry)
                .contextualName("provider-call " + providerName)
                .lowCardinalityKeyValue("provider", providerName)
                .observe(() -> doExecute(providerName, request, bodyParser));
    }

    private <T> ProviderResult<T> doExecute(String providerName, HttpRequest request, Function<String, T> bodyParser) {
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker(providerName);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            T value = breaker.executeCallable(() -> {
                String body = sendWithRetry(providerName, request);
                return bodyParser.apply(body);
            });
            sample.stop(latencyTimer(providerName, "present"));
            log.info("provider call resolved, provider={} uri={} outcome=present", providerName, request.uri());
            return ProviderResult.present(value);
        } catch (CallNotPermittedException e) {
            sample.stop(latencyTimer(providerName, "circuit_open"));
            log.warn("provider circuit breaker open, provider={} uri={}", providerName, request.uri());
            return ProviderResult.absent("circuit breaker open for provider " + providerName);
        } catch (Exception e) {
            sample.stop(latencyTimer(providerName, "absent"));
            log.warn("provider call failed, provider={} uri={} reason={}", providerName, request.uri(), e.toString());
            return ProviderResult.absent(providerName + " call failed, " + e.getMessage());
        }
    }

    private String sendWithRetry(String providerName, HttpRequest request) throws IOException, InterruptedException {
        try {
            return sendOnce(request);
        } catch (IOException e) {
            log.warn("provider call attempt failed, retrying once, provider={} uri={} reason={}",
                    providerName, request.uri(), e.toString());
            return sendOnce(request);
        }
    }

    private String sendOnce(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
        if (response.statusCode() != 200) {
            throw new UnexpectedStatusException(response.statusCode());
        }
        return response.body();
    }

    private Timer latencyTimer(String providerName, String outcome) {
        return Timer.builder("truecost.provider.call.latency")
                .description("Latency of external provider calls made through ResilientHttpClient")
                .tag("provider", providerName)
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    private static final class UnexpectedStatusException extends RuntimeException {
        UnexpectedStatusException(int statusCode) {
            super("unexpected HTTP status " + statusCode);
        }
    }
}
