package com.tpe.paymentorchestrator;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.tpe.contracts.Message;
import com.tpe.messaging.MessageHeaders;
import com.tpe.paymentdomain.MutableClock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
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
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The service on a random port against PostgreSQL and Kafka of the compose stack's versions
 * (deploy/docker-compose.yml) and WireMock as risk-service and ledger-service, started once per JVM; every subclass
 * shares one Spring context. The service's time is {@link #CLOCK}. {@link #http()} never throws on an error status.
 * risk-service answers what {@link #riskAnswers} loaded last, ledger-service what {@link #ledgerAnswers} did.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({OrchestratorStack.TestClock.class, StaffTokens.Config.class})
public abstract class OrchestratorStack {

    /** The service's time (specs/20-testing-strategy.md §2); it does not advance by itself. */
    protected static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-11-12T08:00:00.123Z"));

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    /** risk-service (specs/20-testing-strategy.md §1, §10.4). */
    protected static final WireMockServer RISK = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    /** ledger-service (specs/20-testing-strategy.md §1, §10.4). */
    protected static final WireMockServer LEDGER = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The example hold of P-04, whose path a ledger mapping names; {@link #ledgerAnswers} widens it to any hold. */
    private static final String EXAMPLE_HOLD = "/v1/holds/0192f0e4-7b1c-7cc3-a1b2-0000000000a1/";

    /** The example pending credit of P-04, widened to any pending credit as {@link #EXAMPLE_HOLD} is. */
    private static final String EXAMPLE_PENDING_CREDIT = "/v1/pending-credits/0192f0e4-7b1c-7cc3-a1b2-0000000000a2/";

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
     * risk-service answers the payer's assessment ({@code OUTGOING}) as {@code outgoing} says and the payee's
     * ({@code INCOMING}, UC-04 4a) as {@code incoming}: the same checked mappings, each narrowed to its direction.
     */
    protected static void riskAnswers(String outgoing, String incoming) {
        RISK.resetMappings();
        RISK.addStubMapping(StubMapping.buildFrom(inDirection(mapping("risk-service", outgoing), "OUTGOING")));
        RISK.addStubMapping(StubMapping.buildFrom(inDirection(mapping("risk-service", incoming), "INCOMING")));
    }

    private static String inDirection(String mapping, String direction) {
        ObjectNode tree = (ObjectNode) JSON.readTree(mapping);
        ObjectNode pattern = ((ObjectNode) tree.get("request")).putArray("bodyPatterns").addObject();
        pattern.putObject("matchesJsonPath").put("expression", "$.direction").put("equalTo", direction);
        return JSON.writeValueAsString(tree);
    }

    /**
     * ledger-service answers as the mappings {@code wiremock/ledger-service/<name>.json} say, the later first; the
     * mappings are checked against P-04 by {@code LedgerStubConformanceIT}. A mapping names the pact's example hold or
     * pending credit; here it answers for any.
     */
    protected static void ledgerAnswers(String... names) {
        LEDGER.resetAll();
        for (String name : names) {
            String mapping = mapping("ledger-service", name)
                    .replace("\"url\": \"" + EXAMPLE_HOLD, "\"urlPathPattern\": \"/v1/holds/[0-9a-f-]{36}/")
                    .replace("\"url\": \"" + EXAMPLE_PENDING_CREDIT,
                            "\"urlPathPattern\": \"/v1/pending-credits/[0-9a-f-]{36}/");
            if (mapping.contains(EXAMPLE_HOLD) || mapping.contains(EXAMPLE_PENDING_CREDIT)) {
                throw new IllegalStateException(name + " names the example hold or pending credit other than as "
                        + "\"url\": \"...\"");
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

        /** Off until a test asks for a crash or a hold ({@link ActionInterference}). */
        @Bean
        static BeanPostProcessor actionInterference() {
            return ActionInterference.wrapper();
        }
    }

    @AfterEach
    void stopInterfering() {
        ActionInterference.reset();
    }

    @LocalServerPort
    protected int port;

    /** The service's own mapper: the contracts' value objects as strings. */
    @Autowired
    private JsonMapper contracts;

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

    /**
     * As csm-gateway's relay publishes an event on {@code csm.inbound.events}, key {@code txId}, under
     * {@code eventId}.
     */
    protected void publishCsmEvent(Message event, String txId, UUID eventId) {
        var contract = Message.contractOf(event.getClass());
        publish("csm.inbound.events", txId, contracts.writeValueAsString(event), Map.of(
                MessageHeaders.EVENT_ID, eventId.toString(), MessageHeaders.MESSAGE_TYPE, contract.type(),
                MessageHeaders.SCHEMA_VERSION, Integer.toString(contract.version())));
    }

    /** A record as it is, e.g. one that is no valid event. */
    protected static void publish(String topic, String key, String value, Map<String, String> headers) {
        var record = new ProducerRecord<>(topic, key, value);
        headers.forEach((name, header) -> record.headers().add(name, header.getBytes(StandardCharsets.UTF_8)));
        try (var producer = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(record);
        }
    }

    protected RestClient http() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> {})
                .build();
    }
}
