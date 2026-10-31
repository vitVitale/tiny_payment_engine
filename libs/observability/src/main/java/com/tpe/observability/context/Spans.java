package com.tpe.observability.context;

import io.micrometer.tracing.Link;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Optional;

/**
 * Spans that continue or link to a stored {@code traceparent} (specs/26-observability.md §5, ADR-014): a timer or a
 * saga resume continues the payment's trace ({@link #childOf}); a notification, an account event consumer, a DLT
 * handler or a human action starts its own trace that points back ({@link #linkedTo}). A missing or malformed
 * {@code traceparent} gives a new trace without a link. The caller sets the kind, starts and ends the span.
 */
public final class Spans {

    private final Tracer tracer;
    private final TraceContextCodec codec;

    public Spans(Tracer tracer, TraceContextCodec codec) {
        this.tracer = tracer;
        this.codec = codec;
    }

    /** A span named {@code name} in the trace of {@code traceparent}, as its child. */
    public Span.Builder childOf(String name, String traceparent) {
        Span.Builder builder = tracer.spanBuilder().name(name);
        return codec.decode(traceparent).map(builder::setParent).orElseGet(builder::setNoParent);
    }

    /** A span named {@code name} that starts a new trace and links to {@code traceparent}. */
    public Span.Builder linkedTo(String name, String traceparent) {
        Span.Builder builder = newTrace(name);
        codec.decode(traceparent).ifPresent(context -> builder.addLink(new Link(context)));
        return builder;
    }

    /** A span named {@code name} that starts a new trace, without a link: work with no stored context. */
    public Span.Builder newTrace(String name) {
        return tracer.spanBuilder().name(name).setNoParent();
    }

    /** Puts {@code span} in scope on this thread until the returned scope is closed. */
    public Tracer.SpanInScope inScope(Span span) {
        return tracer.withSpan(span);
    }

    /** The span in scope on this thread, if any. */
    public Optional<Span> current() {
        return Optional.ofNullable(tracer.currentSpan());
    }

    /** The {@code traceparent} of the span in scope, e.g. to store with an outbox row; empty outside a span. */
    public Optional<String> currentTraceparent() {
        return current().flatMap(this::traceparent);
    }

    /** The {@code traceparent} that makes {@code span} the parent; empty for a no-op span. */
    public Optional<String> traceparent(Span span) {
        return span.isNoop() ? Optional.empty() : Optional.of(codec.encode(span.context()));
    }

    /** Without a tracing implementation: spans that record nothing, no {@code traceparent}. */
    public static Spans noop() {
        return new Spans(Tracer.NOOP, new TraceContextCodec(Tracer.NOOP));
    }
}
