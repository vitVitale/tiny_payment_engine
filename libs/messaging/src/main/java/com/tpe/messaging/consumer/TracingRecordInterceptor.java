package com.tpe.messaging.consumer;

import com.tpe.messaging.MessageHeaders;
import com.tpe.messaging.MessageSpans;
import com.tpe.observability.context.Spans;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer.SpanInScope;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.OptionalLong;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * The listener's {@code process <topic>} consumer span (ADR-014, specs/26-observability.md §5.2), in scope while the
 * listener runs, so its JDBC spans and log lines belong to it. The parent rule is per topic: on {@code csm.*} the
 * span continues the record's {@code traceparent} as its parent, so a payment's automated lifecycle stays one trace;
 * on every other topic it starts a new trace with a link to it. Each delivery attempt is a span; a failed attempt's
 * span has an error status. Kafka polls create no spans.
 *
 * <p>Spring Boot sets the {@code RecordInterceptor} bean on its listener container factory. A service must not declare
 * another one: with two, Boot sets neither.
 */
public final class TracingRecordInterceptor implements RecordInterceptor<Object, Object> {

    /** The topics whose consumers continue the producer's trace; all others link to it. */
    static final String CONTINUED_TOPICS_PREFIX = "csm.";

    private final Spans spans;
    private final Clock clock;
    private final ThreadLocal<Processing> current = new ThreadLocal<>();

    public TracingRecordInterceptor(Spans spans, Clock clock) {
        this.spans = spans;
        this.clock = clock;
    }

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
            Consumer<Object, Object> consumer) {
        end();
        String name = "process " + record.topic();
        String traceparent = ConsumedMessages.optionalHeader(record, MessageHeaders.TRACEPARENT);
        Span.Builder builder = record.topic().startsWith(CONTINUED_TOPICS_PREFIX)
                ? spans.childOf(name, traceparent)
                : spans.linkedTo(name, traceparent);
        MessageSpans.withMessage(builder.kind(Span.Kind.CONSUMER), record.topic(),
                record.key() == null ? null : record.key().toString(),
                ConsumedMessages.optionalHeader(record, MessageHeaders.EVENT_ID),
                ConsumedMessages.optionalHeader(record, MessageHeaders.MESSAGE_TYPE));
        ageMillis(record).ifPresent(age -> builder.tag("tpe.outbox.age_ms", age));
        Span span = builder.start();
        current.set(new Processing(span, spans.inScope(span)));
        return record;
    }

    @Override
    public void failure(ConsumerRecord<Object, Object> record, Exception exception,
            Consumer<Object, Object> consumer) {
        Processing processing = current.get();
        if (processing != null) {
            processing.span().error(exception);
        }
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        end();
    }

    /** Closes the scope and ends the span of the record before, if any. */
    private void end() {
        Processing processing = current.get();
        if (processing != null) {
            current.remove();
            processing.scope().close();
            processing.span().end();
        }
    }

    /** Now − {@code tpe-occurred-at}, across the writer's and this host's clocks (skew up to 100 ms, 00 §6). */
    private OptionalLong ageMillis(ConsumerRecord<?, ?> record) {
        String occurredAt = ConsumedMessages.optionalHeader(record, MessageHeaders.OCCURRED_AT);
        if (occurredAt == null) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(clock.millis() - Instant.parse(occurredAt).toEpochMilli());
        } catch (DateTimeParseException e) {
            return OptionalLong.empty();
        }
    }

    private record Processing(Span span, SpanInScope scope) {}
}
