package com.tpe.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.messaging.consumer.ConsumedMessages;
import com.tpe.messaging.inbox.InboxGuard;
import com.tpe.messaging.outbox.OutboxRelay;
import com.tpe.messaging.outbox.OutboxWriter;
import com.tpe.messaging.sample.SampleApplication;
import com.tpe.messaging.sample.SampleApplication.Accepting;
import com.tpe.messaging.worker.PollingWorker;
import com.tpe.observability.test.InMemorySpans;
import com.tpe.paymentdomain.PaymentId;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The per-topic parent rule of ADR-014 and specs/26-observability.md §4.2, §5 with the in-memory span exporter:
 * {@code csm.*} continues the writer's trace through the relay's {@code send} span, other topics start a linked
 * trace; idle loops create no spans. Its own database and service name, so the relays of the other cached contexts
 * do not publish its rows; only its own listener runs, so the sample listeners do not consume what other tests left
 * on their topics.
 */
@SpringBootTest(classes = SampleApplication.class, properties = {
    "tpe.messaging.enabled=true",
    "spring.application.name=tracing-service",
    "spring.kafka.listener.auto-startup=false"
})
@Import({InMemorySpans.Configuration.class, MessagingTracingIT.Listeners.class})
class MessagingTracingIT {

    static final String CSM_TOPIC = "csm.outbound.commands";
    static final String EVENTS_TOPIC = "payments.events";

    private static final AttributeKey<String> EVENT_ID = AttributeKey.stringKey("tpe.event.id");
    private static final AttributeKey<Boolean> DUPLICATE = AttributeKey.booleanKey("tpe.inbox.duplicate");

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        MessagingContainers.register(registry);
        registry.add("spring.datasource.url", () -> MessagingContainers.database("tracing_it"));
    }

    @Autowired
    InMemorySpans spans;

    @Autowired
    Tracer tracer;

    @Autowired
    OutboxWriter outbox;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Listeners listeners;

    @Autowired
    OutboxRelay relay;

    @Autowired
    PollingWorker<?> worker;

    @BeforeEach
    void reset() {
        spans.reset();
    }

    @Test
    void aCsmMessageContinuesTheWritersTrace() {
        Span writer = tracer.nextSpan().name("write").start();
        UUID eventId = writeIn(writer, CSM_TOPIC);

        SpanData send = spans.await(span("send " + CSM_TOPIC, eventId));
        SpanData process = spans.await(span("process " + CSM_TOPIC, eventId));

        assertThat(send.getKind()).isEqualTo(SpanKind.PRODUCER);
        assertThat(send.getTraceId()).isEqualTo(writer.context().traceId());
        assertThat(send.getParentSpanId()).isEqualTo(writer.context().spanId());
        assertThat(process.getKind()).isEqualTo(SpanKind.CONSUMER);
        assertThat(process.getTraceId()).isEqualTo(writer.context().traceId());
        assertThat(process.getParentSpanId()).isEqualTo(send.getSpanId());
        assertThat(process.getAttributes().get(DUPLICATE)).isFalse();
    }

    @Test
    void aRedeliveredMessageIsMarkedDuplicate() {
        Span writer = tracer.nextSpan().name("write").start();
        UUID eventId = writeIn(writer, CSM_TOPIC);
        spans.await(span("process " + CSM_TOPIC, eventId));

        redeliver(listeners.records.get(eventId));

        spans.await(span("process " + CSM_TOPIC, eventId).and(span -> Boolean.TRUE.equals(
                span.getAttributes().get(DUPLICATE))));
    }

    @Test
    void anEventStartsANewTraceLinkedToTheSend() {
        Span writer = tracer.nextSpan().name("write").start();
        UUID eventId = writeIn(writer, EVENTS_TOPIC);

        SpanData send = spans.await(span("send " + EVENTS_TOPIC, eventId));
        SpanData process = spans.await(span("process " + EVENTS_TOPIC, eventId));

        assertThat(send.getTraceId()).isEqualTo(writer.context().traceId());
        assertThat(process.getTraceId()).isNotEqualTo(send.getTraceId());
        assertThat(process.getParentSpanContext().isValid()).isFalse();
        assertThat(process.getLinks()).extracting(LinkData::getSpanContext)
                .anySatisfy(linked -> {
                    assertThat(linked.getTraceId()).isEqualTo(send.getTraceId());
                    assertThat(linked.getSpanId()).isEqualTo(send.getSpanId());
                });
    }

    @Test
    void thePublishedTraceparentIsTheSendSpan() {
        Span writer = tracer.nextSpan().name("write").start();
        UUID eventId = writeIn(writer, CSM_TOPIC);

        SpanData send = spans.await(span("send " + CSM_TOPIC, eventId));
        Topics.await(Duration.ofSeconds(10), () -> listeners.traceparents.containsKey(eventId));

        assertThat(listeners.traceparents.get(eventId))
                .isEqualTo("00-" + send.getTraceId() + "-" + send.getSpanId() + "-01");
    }

    @Test
    void idleRelayAndWorkerCreateNoSpans() throws InterruptedException {
        Topics.await(Duration.ofSeconds(10), relay::isLeader);
        spans.reset();

        Thread.sleep(Duration.ofSeconds(10));

        assertThat(relay.isLeader()).as("the relay polled as leader throughout").isTrue();
        assertThat(worker.isRunning()).as("the worker polled throughout").isTrue();
        assertThat(spans.finished()).extracting(SpanData::getName).isEmpty();
    }

    @Test
    void aHandledWorkerRowIsOneSpan() throws InterruptedException {
        long id = jdbc.queryForObject("INSERT INTO sample_timers (due_at) VALUES (?) RETURNING id", Long.class,
                OffsetDateTime.ofInstant(Instant.now().minusSeconds(1), ZoneOffset.UTC));
        Topics.await(Duration.ofSeconds(10), () -> "FIRED".equals(
                jdbc.queryForObject("SELECT status FROM sample_timers WHERE id = ?", String.class, id)));
        spans.await(span -> span.getName().equals("worker sample-timers"));
        Thread.sleep(Duration.ofSeconds(2));

        assertThat(spans.finished()).filteredOn(span -> !span.getParentSpanContext().isValid())
                .extracting(SpanData::getName).containsExactly("worker sample-timers");
        assertThat(spans.finished()).filteredOn(span -> span.getName().startsWith("worker "))
                .singleElement().extracting(SpanData::getKind).isEqualTo(SpanKind.INTERNAL);
    }

    /** Writes a message to {@code topic} in a transaction inside {@code writer}, then ends {@code writer}. */
    private UUID writeIn(Span writer, String topic) {
        try (Tracer.SpanInScope scope = tracer.withSpan(writer)) {
            PaymentId payment = new PaymentId(UUID.randomUUID());
            return new TransactionTemplate(transactions).execute(status ->
                    outbox.write(topic, payment.value().toString(), Accepting.message(payment)));
        } finally {
            writer.end();
        }
    }

    /** The record as it was consumed, sent again: what a relay failover does. */
    private static void redeliver(ConsumerRecord<String, String> record) {
        try (var producer = new KafkaProducer<>(Map.<String, Object>of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, MessagingContainers.KAFKA.getBootstrapServers()),
                new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(record.topic(), null, record.key(), record.value(), record.headers()))
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Predicate<SpanData> span(String name, UUID eventId) {
        return span -> span.getName().equals(name) && eventId.toString().equals(span.getAttributes().get(EVENT_ID));
    }

    /** Consumes both topics through the inbox and keeps each message's first record and its {@code traceparent}. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Listeners {

        final Map<UUID, String> traceparents = new ConcurrentHashMap<>();
        final Map<UUID, ConsumerRecord<String, String>> records = new ConcurrentHashMap<>();

        private final InboxGuard inbox;
        private final TransactionTemplate transactions;

        Listeners(InboxGuard inbox, PlatformTransactionManager transactions) {
            this.inbox = inbox;
            this.transactions = new TransactionTemplate(transactions);
        }

        @KafkaListener(topics = {CSM_TOPIC, EVENTS_TOPIC}, autoStartup = "true")
        void onMessage(ConsumerRecord<String, String> record) {
            UUID eventId = ConsumedMessages.eventId(record);
            records.putIfAbsent(eventId, record);
            traceparents.putIfAbsent(eventId, ConsumedMessages.optionalHeader(record, MessageHeaders.TRACEPARENT));
            transactions.executeWithoutResult(status -> inbox.firstTime(eventId.toString(), record.topic(), eventId));
        }
    }
}
