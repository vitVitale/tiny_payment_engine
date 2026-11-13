package com.tpe.paymentorchestrator;

import com.tpe.paymentdomain.MutableClock;
import java.time.Instant;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The service on a random port against PostgreSQL and Kafka of the compose stack's versions
 * (deploy/docker-compose.yml), started once per JVM; every subclass shares one Spring context. The service's time is
 * {@link #CLOCK}. {@link #http()} never throws on an error status.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(OrchestratorStack.TestClock.class)
public abstract class OrchestratorStack {

    /** The service's time (specs/20-testing-strategy.md §2); it does not advance by itself. */
    protected static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-11-12T08:00:00.123Z"));

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClock {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return CLOCK;
        }
    }

    @LocalServerPort
    protected int port;

    @DynamicPropertySource
    static void stack(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // spans are made, so the submit's trace is stored (ADR-014), but there is no collector to send them to
        registry.add("management.tracing.export.enabled", () -> "false");
    }

    protected RestClient http() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> {})
                .build();
    }
}
