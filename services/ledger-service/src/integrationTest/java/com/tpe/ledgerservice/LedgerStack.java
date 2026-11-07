package com.tpe.ledgerservice;

import com.tpe.paymentdomain.MutableClock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * The service on a random port against PostgreSQL and Kafka of the compose stack's versions (deploy/docker-compose.yml),
 * started once per JVM; every subclass shares one Spring context. {@link #http()} never throws on an error status.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({StaffTokens.Config.class, LedgerStack.TestClock.class})
public abstract class LedgerStack {

    /** The service's time (specs/20-testing-strategy.md §2); it does not advance by itself. */
    protected static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-01-05T08:00:00.123Z"));

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClock {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return CLOCK;
        }
    }

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void stack(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Value("${local.server.port}")
    protected int port;

    protected RestClient http() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> {})
                .build();
    }

    /**
     * {@code POST /v1/accounts}; {@code authorization} and {@code idempotencyKey} are left out when {@code null}.
     */
    protected ResponseEntity<JsonNode> open(String authorization, String idempotencyKey, Map<String, ?> body) {
        var request = http().post().uri("/v1/accounts").contentType(MediaType.APPLICATION_JSON).body(body);
        if (authorization != null) {
            request.header(AUTHORIZATION, authorization);
        }
        if (idempotencyKey != null) {
            request.header(IDEMPOTENCY_KEY, idempotencyKey);
        }
        return request.retrieve().toEntity(JsonNode.class);
    }

    /** A key no other request used. */
    protected static String newIdempotencyKey() {
        return "it-" + UUID.randomUUID();
    }

    /** A back-office operator's {@code Authorization} header value. */
    protected static String operator(String subject) {
        return bearer(StaffTokens.token(subject, "BACKOFFICE_OPERATOR"));
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    protected static final String AUTHORIZATION = HttpHeaders.AUTHORIZATION;
    protected static final String IDEMPOTENCY_KEY = "Idempotency-Key";
}
