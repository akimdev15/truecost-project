package com.truecost.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises RouteClient's HTTP call and JSON parsing against a canned OSRM shaped response
 * served by a local embedded HTTP server, so the wire contract is tested without depending on a
 * live OSRM instance or the network in ./gradlew test. The response body below uses the same
 * encoded polyline canonical example PolylineDecoderTest verifies, and a two point route so the
 * annotation duration array has exactly one value.
 */
class RouteClientTest {

    private HttpServer server;
    private RouteClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
        client = new RouteClient("http://localhost:" + server.getAddress().getPort(), 5, io.micrometer.observation.ObservationRegistry.NOOP);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void parsesDistanceDurationAndDecodedGeometryFromAnOkResponse() {
        String body = """
                {
                  "code": "Ok",
                  "routes": [
                    {
                      "distance": 12345.6,
                      "duration": 987.0,
                      "geometry": "_p~iF~ps|U_ulLnnqC",
                      "legs": [
                        { "annotation": { "duration": [500.0] } }
                      ]
                    }
                  ]
                }
                """;
        server.createContext("/route/v1/driving/", exchange -> respond(exchange, 200, body));

        Route route = client.fetchRoute(38.5, -120.2, 40.7, -120.95);

        assertThat(route.distanceMeters()).isEqualTo(12345.6);
        assertThat(route.durationSeconds()).isEqualTo(987.0);
        assertThat(route.points()).hasSize(2);
        assertThat(route.points().get(0).lat()).isCloseTo(38.5, within(1e-5));
        assertThat(route.points().get(0).cumulativeDurationSeconds()).isEqualTo(0.0);
        assertThat(route.points().get(1).lat()).isCloseTo(40.7, within(1e-5));
        assertThat(route.points().get(1).cumulativeDurationSeconds()).isEqualTo(500.0);
    }

    @Test
    void throwsWhenOsrmReturnsANonOkCode() {
        String body = """
                { "code": "NoRoute", "message": "no route found between points" }
                """;
        server.createContext("/route/v1/driving/", exchange -> respond(exchange, 200, body));

        assertThatThrownBy(() -> client.fetchRoute(38.5, -120.2, 40.7, -120.95))
                .isInstanceOf(RouteClientException.class)
                .hasMessageContaining("NoRoute");
    }

    @Test
    void throwsWhenOsrmReturnsAnHttpErrorStatus() {
        server.createContext("/route/v1/driving/", exchange -> respond(exchange, 500, "internal error"));

        assertThatThrownBy(() -> client.fetchRoute(38.5, -120.2, 40.7, -120.95))
                .isInstanceOf(RouteClientException.class)
                .hasMessageContaining("500");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
