package com.tpe.vopservice;

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
 * The service on a random port against PostgreSQL and Kafka of the compose stack's versions (deploy/docker-compose.yml),
 * started once per JVM; every subclass shares one Spring context. The service's time is {@link #CLOCK}; the responder
 * audit gets a test pepper; the bank directory is the default, deploy/config/bank-directory.yml; the retention job
 * polls every 200 ms. {@link #http()} never throws on an error status.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(VopStack.TestClock.class)
public abstract class VopStack {

    /** The service's time (specs/20-testing-strategy.md §2); it does not advance by itself. */
    protected static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-11-12T08:00:00.123Z"));

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

    /** {@code tpe.vop.active-pepper-version} and its pepper; version 1 is a retired one. */
    protected static final int ACTIVE_PEPPER_VERSION = 2;
    protected static final String ACTIVE_PEPPER = "component-test-pepper-v2";

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void stack(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("tpe.vop.audit-peppers.1", () -> "component-test-pepper-v1");
        registry.add("tpe.vop.audit-peppers.2", () -> ACTIVE_PEPPER);
        registry.add("tpe.vop.active-pepper-version", () -> ACTIVE_PEPPER_VERSION);
        registry.add("tpe.vop.retention.poll-interval", () -> "200ms");
    }

    protected RestClient http() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> {})
                .build();
    }
}
