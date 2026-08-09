package com.truecost.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Calls the self hosted OSRM HTTP routing API for full overview geometry with per segment
 * duration annotations, so the returned route carries a cumulative duration at every polyline
 * point, which TollTimeline uses to turn a detected crossing into an estimated arrival instant.
 */
@Component
public class RouteClient {

    private static final Logger log = LoggerFactory.getLogger(RouteClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final Duration timeout;
    private final ObservationRegistry observationRegistry;

    public RouteClient(
            @Value("${truecost.osrm.base-url:http://localhost:5001}") String baseUrl,
            @Value("${truecost.osrm.timeout-seconds:5}") long timeoutSeconds,
            ObservationRegistry observationRegistry) {
        this.baseUrl = baseUrl;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.observationRegistry = observationRegistry;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public Route fetchRoute(double originLat, double originLng, double destLat, double destLng) {
        return Observation.createNotStarted("osrm.route", observationRegistry)
                .contextualName("osrm-route")
                .observe(() -> doFetchRoute(originLat, originLng, destLat, destLng));
    }

    private Route doFetchRoute(double originLat, double originLng, double destLat, double destLng) {
        URI uri = buildRouteUri(originLat, originLng, destLat, destLng);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.warn("osrm call failed, baseUrl={} reason={}", baseUrl, e.toString());
            throw new RouteClientException("failed to reach OSRM at " + baseUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RouteClientException("interrupted while waiting for OSRM response", e);
        }

        if (response.statusCode() != 200) {
            throw new RouteClientException(
                    "OSRM returned HTTP " + response.statusCode() + " for " + uri);
        }

        Route route = parseRoute(response.body());
        log.info("osrm route fetched, originLat={} originLng={} destLat={} destLng={} "
                        + "distanceMeters={} durationSeconds={} points={}",
                originLat, originLng, destLat, destLng, route.distanceMeters(), route.durationSeconds(),
                route.points().size());
        return route;
    }

    private URI buildRouteUri(double originLat, double originLng, double destLat, double destLng) {
        String coordinates = String.format(Locale.ROOT, "%f,%f;%f,%f", originLng, originLat, destLng, destLat);
        String path = String.format(Locale.ROOT,
                "%s/route/v1/driving/%s?overview=full&geometries=polyline&annotations=duration",
                baseUrl, coordinates);
        return URI.create(path);
    }

    private Route parseRoute(String responseBody) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (IOException e) {
            throw new RouteClientException("could not parse OSRM response as JSON", e);
        }

        String code = root.path("code").asText("");
        if (!"Ok".equals(code)) {
            String message = root.path("message").asText("no route found");
            throw new RouteClientException("OSRM returned code " + code + ", " + message);
        }

        JsonNode routeNode = root.path("routes").path(0);
        if (routeNode.isMissingNode()) {
            throw new RouteClientException("OSRM response had no routes");
        }

        double distanceMeters = routeNode.path("distance").asDouble();
        double durationSeconds = routeNode.path("duration").asDouble();
        String encodedGeometry = routeNode.path("geometry").asText();

        List<Double> segmentDurations = collectSegmentDurations(routeNode.path("legs"));
        List<double[]> latLngPoints = PolylineDecoder.decode(encodedGeometry);
        List<RoutePoint> points = zipWithCumulativeDuration(latLngPoints, segmentDurations, durationSeconds);

        return new Route(points, distanceMeters, durationSeconds);
    }

    /**
     * Flattens the per leg annotation.duration arrays into one list covering the whole route.
     * There is one duration value per polyline segment, so N geometry points yield N minus one
     * durations, except when OSRM omits annotations, in which case duration is spread evenly
     * across segments as a fallback so cumulative duration is still monotonic and usable.
     */
    private List<Double> collectSegmentDurations(JsonNode legsNode) {
        List<Double> durations = new ArrayList<>();
        if (legsNode.isArray()) {
            for (JsonNode leg : legsNode) {
                JsonNode durationArray = leg.path("annotation").path("duration");
                if (durationArray.isArray()) {
                    for (JsonNode d : durationArray) {
                        durations.add(d.asDouble());
                    }
                }
            }
        }
        return durations;
    }

    private List<RoutePoint> zipWithCumulativeDuration(
            List<double[]> latLngPoints, List<Double> segmentDurations, double totalDurationSeconds) {
        List<RoutePoint> points = new ArrayList<>(latLngPoints.size());
        boolean hasAnnotations = segmentDurations.size() == latLngPoints.size() - 1 && !latLngPoints.isEmpty();
        double fallbackSegmentDuration = latLngPoints.size() > 1
                ? totalDurationSeconds / (latLngPoints.size() - 1)
                : 0.0;

        if (!hasAnnotations && !latLngPoints.isEmpty()) {
            log.warn("osrm response missing per segment duration annotations, "
                    + "cumulative duration is spread evenly across {} points as a fallback", latLngPoints.size());
        }

        double cumulative = 0.0;
        for (int i = 0; i < latLngPoints.size(); i++) {
            double[] latLng = latLngPoints.get(i);
            if (i > 0) {
                cumulative += hasAnnotations ? segmentDurations.get(i - 1) : fallbackSegmentDuration;
            }
            points.add(new RoutePoint(latLng[0], latLng[1], cumulative));
        }
        return points;
    }
}
