package com.tpe.paymentorchestrator;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.tpe.paymentdomain.MutableClock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
 * (deploy/docker-compose.yml) and WireMock as risk-service and ledger-service, started once per JVM; every subclass
 * shares one Spring context. The service's time is {@link #CLOCK}. {@link #http()} never throws on an error status.
 * risk-service answers what {@link #riskAnswers} loaded last, ledger-service what {@link #ledgerAnswers} did.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(OrchestratorStack.TestClock.class)
public abstract class OrchestratorStack {

    /** The service's time (specs/20-testing-strategy.md §2); it does not advance by itself. */
    protected static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-11-12T08:00:00.123Z"));

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    /** risk-service (specs/20-testing-strategy.md §1, §10.4). */
    protected static final WireMockServer RISK = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    /** ledger-service (specs/20-testing-strategy.md §1, §10.4). */
    protected static final WireMockServer LEDGER = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    /** The example hold of P-04, whose path a ledger mapping names; {@link #ledgerAnswers} widens it to any hold. */
    private static final String EXAMPLE_HOLD = "/v1/holds/0192f0e4-7b1c-7cc3-a1b2-0000000000a1/";

    static {
        POSTGRES.start();
        KAFKA.start();
        RISK.start();
        LEDGER.start();
    }

    /**
     * risk-service answers every assessment as the mapping {@code wiremock/risk-service/<name>.json} says; the mappings
     * are checked against P-03 by {@code RiskStubConformanceIT}.
     */
    protected static void riskAnswers(String name) {
        RISK.resetMappings();
        RISK.addStubMapping(StubMapping.buildFrom(mapping("risk-service", name)));
    }

    /**
     * ledger-service answers as the mappings {@code wiremock/ledger-service/<name>.json} say, the later first; the
     * mappings are checked against P-04 by {@code LedgerStubConformanceIT}. A mapping names the pact's example hold;
     * here it answers for any hold.
     */
    protected static void ledgerAnswers(String... names) {
        LEDGER.resetAll();
        for (String name : names) {
            String mapping = mapping("ledger-service", name)
                    .replace("\"url\": \"" + EXAMPLE_HOLD, "\"urlPathPattern\": \"/v1/holds/[0-9a-f-]{36}/");
            if (mapping.contains(EXAMPLE_HOLD)) {
                throw new IllegalStateException(name + " names the example hold other than as \"url\": \"...\"");
            }
            LEDGER.addStubMapping(StubMapping.buildFrom(mapping));
        }
    }

    private static String mapping(String provider, String name) {
        String path = "/wiremock/" + provider + "/" + name + ".json";
        try (var mapping = OrchestratorStack.class.getResourceAsStream(path)) {
            return new String(mapping.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
        registry.add("tpe.clients.risk.base-url", RISK::baseUrl);
        registry.add("tpe.clients.ledger.base-url", LEDGER::baseUrl);
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
