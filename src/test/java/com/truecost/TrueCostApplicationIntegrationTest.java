package com.truecost;

import com.jayway.jsonpath.JsonPath;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Spring context assembles against real Postgres and Redis and that
 * Actuator reports both as UP, without depending on the compose stack being up.
 * Testcontainers starts and tears down its own containers for this test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TrueCostApplicationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    @Container
    @ServiceConnection
    static RedisContainer redis = new RedisContainer(DockerImageName.parse("redis:7"));

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthReportsPostgresAndRedisUp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        String body = response.getBody();
        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("UP");
        assertThat(JsonPath.<String>read(body, "$.components.db.status")).isEqualTo("UP");
        assertThat(JsonPath.<String>read(body, "$.components.redis.status")).isEqualTo("UP");
    }
}
