package com.tpe.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.messaging.MessageHeaders;
import com.tpe.observability.context.Spans;
import com.tpe.observability.context.TraceContextCodec;
import io.micrometer.tracing.test.simple.SimpleSpan;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TracingRecordInterceptorTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";
    private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");

    private final SimpleTracer tracer = new SimpleTracer();
    private final TracingRecordInterceptor interceptor = new TracingRecordInterceptor(
            new Spans(tracer, new TraceContextCodec(tracer)), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void theProcessSpanIsInScopeWhileTheListenerRunsAndEndsAfterIt() {
        ConsumerRecord<Object, Object> record = record("csm.outbound.commands");

        interceptor.intercept(record, null);
        SimpleSpan current = (SimpleSpan) tracer.currentSpan();
        interceptor.success(record, null);
        interceptor.afterRecord(record, null);

        assertThat(current.getName()).isEqualTo("process csm.outbound.commands");
        assertThat(current.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(current.getTags()).containsEntry("tpe.event.id", "0192d3c4-0000-7000-8000-000000000001")
                .containsEntry("tpe.outbox.age_ms", "250");
        assertThat(current.getEndTimestamp()).isNotEqualTo(Instant.ofEpochMilli(0));
        assertThat(tracer.currentSpan()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"payments.events", "accounts.events"})
    void otherTopicsStartANewTrace(String topic) {
        ConsumerRecord<Object, Object> record = record(topic);

        interceptor.intercept(record, null);
        SimpleSpan current = (SimpleSpan) tracer.currentSpan();
        interceptor.afterRecord(record, null);

        assertThat(current.getTraceId()).isNotEqualTo(TRACE_ID);
    }

    @Test
    void aFailedAttemptHasAnErrorStatus() {
        ConsumerRecord<Object, Object> record = record("csm.inbound.events");
        IllegalStateException failure = new IllegalStateException("listener failed");

        interceptor.intercept(record, null);
        SimpleSpan current = (SimpleSpan) tracer.currentSpan();
        interceptor.failure(record, failure, null);
        interceptor.afterRecord(record, null);

        assertThat(current.getError()).isSameAs(failure);
    }

    @Test
    void aRecordWithoutAKeyHasNoKeyAttribute() {
        ConsumerRecord<Object, Object> record = new ConsumerRecord<>("payments.events", 0, 0L, null, "{}");

        interceptor.intercept(record, null);
        SimpleSpan current = (SimpleSpan) tracer.currentSpan();
        interceptor.afterRecord(record, null);

        assertThat(current.getTags()).doesNotContainKey("messaging.kafka.message.key");
    }

    private static ConsumerRecord<Object, Object> record(String topic) {
        ConsumerRecord<Object, Object> record = new ConsumerRecord<>(topic, 0, 0L, "key", "{}");
        header(record, MessageHeaders.TRACEPARENT, TRACEPARENT);
        header(record, MessageHeaders.EVENT_ID, "0192d3c4-0000-7000-8000-000000000001");
        header(record, MessageHeaders.MESSAGE_TYPE, "SendCreditTransfer");
        header(record, MessageHeaders.OCCURRED_AT, NOW.minusMillis(250).toString());
        return record;
    }

    private static void header(ConsumerRecord<Object, Object> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
