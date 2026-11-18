package com.tpe.csmgateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.tpe.contracts.Message;
import com.tpe.messaging.MessageHeaders;
import com.tpe.paymentdomain.MutableClock;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The gateway on a random port against PostgreSQL and Kafka of the compose stack's versions
 * (deploy/docker-compose.yml) and WireMock as the CSM ({@code 202} for every message unless a test says otherwise),
 * started once per JVM; every subclass shares one Spring context. Every test starts with no transfers, journal rows
 * or outbox rows and the service's time at {@link #START} ({@link #CLOCK}). Staff tokens are {@link StaffTokens}'.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({GatewayStack.TestClock.class, StaffTokens.Config.class})
public abstract class GatewayStack {

    protected static final Instant START = Instant.parse("2026-11-15T10:00:00.000Z");

    /** The service's time (specs/20-testing-strategy.md §2); it does not advance by itself. */
    protected static final MutableClock CLOCK = new MutableClock(START);

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    /** The CSM's {@code POST /csm/v1/messages}. */
    protected static final WireMockServer CSM = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        POSTGRES.start();
        KAFKA.start();
        CSM.start();
    }

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    private JsonMapper json;

    @LocalServerPort
    protected int port;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClock {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return CLOCK;
        }
    }

    @DynamicPropertySource
    static void stack(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("tpe.csm.base-url", CSM::baseUrl);
        // spans are made, so the payment's trace is stored (ADR-014), but there is no collector to send them to
        registry.add("management.tracing.export.enabled", () -> "false");
    }

    @BeforeEach
    void cleanSlate() {
        CLOCK.set(START);
        jdbc.sql("DELETE FROM status_requests").update();
        jdbc.sql("DELETE FROM incoming_transfers").update();
        jdbc.sql("DELETE FROM outgoing_transfers").update();
        jdbc.sql("DELETE FROM message_journal").update();
        jdbc.sql("DELETE FROM outbox").update();
        jdbc.sql("DELETE FROM inbox").update();
        CSM.resetAll();
        CSM.stubFor(post("/csm/v1/messages").willReturn(aResponse().withStatus(202)));
    }

    /** What the gateway sent to the CSM, oldest first. */
    protected static List<String> sentToCsm() {
        return CSM.findAll(postRequestedFor(urlEqualTo("/csm/v1/messages"))).stream()
                .map(LoggedRequest::getBodyAsString).toList();
    }

    /** As the orchestrator's relay publishes a command on {@code csm.outbound.commands}, under {@code eventId}. */
    protected void publishCommand(String key, Message message, UUID eventId) {
        var record = new ProducerRecord<>("csm.outbound.commands", key, json.writeValueAsString(message));
        var contract = Message.contractOf(message.getClass());
        record.headers().add(MessageHeaders.EVENT_ID, bytes(eventId.toString()));
        record.headers().add(MessageHeaders.MESSAGE_TYPE, bytes(contract.type()));
        record.headers().add(MessageHeaders.SCHEMA_VERSION, bytes(Integer.toString(contract.version())));
        try (var producer = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(record);
        }
    }

    /** The events written to the outbox for {@code csm.inbound.events} under key {@code txId}, oldest first. */
    protected List<Event> events(String txId) {
        return jdbc.sql("""
                SELECT message_type, payload::text AS payload FROM outbox
                 WHERE topic = 'csm.inbound.events' AND message_key = :txId ORDER BY id""")
                .param("txId", txId)
                .query((rs, n) -> new Event(rs.getString("message_type"), rs.getString("payload"))).list();
    }

    /** The {@code outgoing_transfers} row of {@code txId}, empty if there is none. */
    protected Map<String, Object> transfer(String txId) {
        return jdbc.sql("SELECT * FROM outgoing_transfers WHERE tx_id = :txId").param("txId", txId).query()
                .listOfRows().stream().findFirst().orElse(Map.of());
    }

    /** A message from the CSM to {@code POST /csm/v1/inbound}. */
    protected org.springframework.http.ResponseEntity<String> inbound(String envelope) {
        return http().post().uri("/csm/v1/inbound").contentType(MediaType.APPLICATION_XML).body(envelope)
                .retrieve().toEntity(String.class);
    }

    /** Never throws on an error status. */
    protected RestClient http() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> {})
                .build();
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    /** An outbox event: its type and JSON payload. */
    protected record Event(String type, String payload) {}

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
