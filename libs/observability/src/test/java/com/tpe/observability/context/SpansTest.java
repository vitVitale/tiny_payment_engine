package com.tpe.observability.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.Test;

class SpansTest {

    private final SimpleTracer tracer = new SimpleTracer();
    private final Spans spans = new Spans(tracer, new TraceContextCodec(tracer));

    @Test
    void theCurrentTraceparentIsTheSpanInScope() {
        Span span = tracer.nextSpan().start();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            assertThat(spans.currentTraceparent()).contains(new TraceContextCodec(tracer).encode(span.context()));
        } finally {
            span.end();
        }
    }

    @Test
    void noCurrentTraceparentOutsideASpan() {
        assertThat(spans.currentTraceparent()).isEmpty();
    }

    @Test
    void theTraceparentOfASpanIsItsOwnContext() {
        Span span = spans.childOf("send", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01").start();

        assertThat(spans.traceparent(span)).hasValueSatisfying(traceparent -> assertThat(traceparent)
                .startsWith("00-4bf92f3577b34da6a3ce929d0e0e4736-").doesNotContain("00f067aa0ba902b7"));
    }

    @Test
    void aNewTraceHasNoParentEvenInsideASpan() {
        Span outer = tracer.nextSpan().start();
        try (Tracer.SpanInScope scope = spans.inScope(outer)) {
            Span root = spans.newTrace("worker timers").start();

            assertThat(root.context().traceId()).isNotEqualTo(outer.context().traceId());
            assertThat(spans.current()).contains(outer);
        } finally {
            outer.end();
        }
        assertThat(spans.current()).isEmpty();
    }

    @Test
    void theNoopSpansGiveNoTraceparent() {
        Spans noop = Spans.noop();
        Span span = noop.childOf("send", null).start();

        assertThat(noop.traceparent(span)).isEmpty();
        assertThat(noop.currentTraceparent()).isEmpty();
    }
}
