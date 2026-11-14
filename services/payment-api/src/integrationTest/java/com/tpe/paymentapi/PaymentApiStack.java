package com.tpe.paymentapi;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.tpe.paymentdomain.MutableClock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The service on a random port against PostgreSQL of the compose stack's version (deploy/docker-compose.yml), with
 * WireMock as payment-orchestrator and vop-service, started once per JVM; every subclass shares one Spring context.
 * Every test starts with no idempotency keys or rejections, no WireMock mappings or requests, and the service's time at
 * {@link #START} ({@link #CLOCK}). Tokens are
 * {@link Tokens}'; the bank directory is the default, deploy/config/bank-directory.yml. The orchestrator answers what
 * {@link #orchestratorAnswers} loaded last, vop-service what {@link #vopAnswers} did. {@link #http()} never throws on
 * an error status and never repeats a request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PaymentApiStack.TestClock.class)
public abstract class PaymentApiStack {

    protected static final Instant START = Instant.parse("2026-11-14T10:00:00.123Z");

    /** The service's time (specs/20-testing-strategy.md §2); it does not advance by itself. */
    protected static final MutableClock CLOCK = new MutableClock(START);

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    /** payment-orchestrator (specs/20-testing-strategy.md §1, §10.4). */
    protected static final WireMockServer ORCHESTRATOR = new WireMockServer(
            WireMockConfiguration.options().dynamicPort());

    /** vop-service (specs/20-testing-strategy.md §1, §10.4). */
    protected static final WireMockServer VOP = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    /** The example reference of P-02 a vop-service mapping names; {@link #vopAnswers} widens it to any reference. */
    private static final String EXAMPLE_CHECK = "\"url\": \"/v1/verifications/VOP-01JABCDEFGHJKMNPQRSTVW000";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static {
        POSTGRES.start();
        ORCHESTRATOR.start();
        VOP.start();
    }

    @LocalServerPort
    protected int port;

    @Autowired
    private JdbcClient jdbc;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClock {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return CLOCK;
        }
    }

    @BeforeEach
    void noKeysAtTheStart() {
        jdbc.sql("DELETE FROM idempotency_keys").update();
        jdbc.sql("DELETE FROM payment_rejections").update();
        CLOCK.set(START);
        ORCHESTRATOR.resetAll();
        VOP.resetAll();
    }

    @DynamicPropertySource
    static void stack(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.public-key-location", Tokens::publicKeyLocation);
        registry.add("tpe.clients.orchestrator.base-url", ORCHESTRATOR::baseUrl);
        registry.add("tpe.clients.vop.base-url", VOP::baseUrl);
        // the verify fault mapping answers after 1.5 s, not after the 5.5 s of production
        registry.add("tpe.clients.vop.verify-timeout", () -> "1s");
    }

    /**
     * The orchestrator answers as the mappings {@code wiremock/payment-orchestrator/<name>.json} say, the later first;
     * the mappings are checked against P-01 by {@code OrchestratorStubConformanceIT}.
     */
    protected static void orchestratorAnswers(String... names) {
        ORCHESTRATOR.resetAll();
        for (String name : names) {
            ORCHESTRATOR.addStubMapping(StubMapping.buildFrom(mapping("payment-orchestrator", name)));
        }
    }

    /** As {@link #orchestratorAnswers}, with one mapping answering only after {@code delay}. */
    protected static void orchestratorAnswersAfter(Duration delay, String name) {
        ORCHESTRATOR.resetAll();
        ObjectNode mapping = (ObjectNode) JSON.readTree(mapping("payment-orchestrator", name));
        ((ObjectNode) mapping.get("response")).put("fixedDelayMilliseconds", delay.toMillis());
        ORCHESTRATOR.addStubMapping(StubMapping.buildFrom(JSON.writeValueAsString(mapping)));
    }

    /**
     * vop-service answers as {@code wiremock/vop-service/<name>.json} says, for any reference; the mappings are
     * checked against P-02 by {@code VopStubConformanceIT} and name the pact's example references.
     */
    protected static void vopAnswers(String name) {
        VOP.resetAll();
        String mapping = mapping("vop-service", name);
        if (mapping.contains("/v1/verifications/VOP-")) {
            int start = mapping.indexOf(EXAMPLE_CHECK);
            if (start < 0) {
                throw new IllegalStateException(name + " names the example reference other than as \"url\": \"...\"");
            }
            int end = mapping.indexOf("/check\"", start);
            mapping = mapping.substring(0, start) + "\"urlPathPattern\": \"/v1/verifications/[^/]+/check\""
                    + mapping.substring(end + "/check\"".length());
        }
        VOP.addStubMapping(StubMapping.buildFrom(mapping));
    }

    private static String mapping(String provider, String name) {
        String path = "/wiremock/" + provider + "/" + name + ".json";
        try (var mapping = PaymentApiStack.class.getResourceAsStream(path)) {
            if (mapping == null) {
                throw new IllegalArgumentException("no mapping " + path);
            }
            return new String(mapping.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The JDK's client, which repeats nothing: the default on this classpath, Apache HttpClient, repeats a request
     * answered {@code 503} with {@code Retry-After} by itself.
     */
    protected RestClient http() {
        return RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> {})
                .build();
    }
}
