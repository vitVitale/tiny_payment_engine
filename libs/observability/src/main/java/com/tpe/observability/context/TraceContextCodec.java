package com.tpe.observability.context;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Span context ↔ W3C {@code traceparent} string (specs/26-observability.md §4): what an outbox row, a Kafka header or
 * a {@code trace_context} column stores so a later step continues or links to the trace.
 */
public final class TraceContextCodec {

    /** version-traceid-parentid-flags; a version above 00 may carry more fields after the flags. */
    private static final Pattern TRACEPARENT =
            Pattern.compile("([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})(-.*)?");
    private static final String INVALID_TRACE_ID = "0".repeat(32);
    private static final String INVALID_SPAN_ID = "0".repeat(16);

    private final Tracer tracer;

    public TraceContextCodec(Tracer tracer) {
        this.tracer = tracer;
    }

    /** {@code 00-<traceId>-<spanId>-<01 if sampled, else 00>}. */
    public String encode(TraceContext context) {
        return "00-" + context.traceId() + "-" + context.spanId() + "-" + (Boolean.TRUE.equals(context.sampled())
                ? "01" : "00");
    }

    /** The context of a valid {@code traceparent}; empty for a missing or malformed one (W3C trace-context §4.3). */
    public Optional<TraceContext> decode(String traceparent) {
        if (traceparent == null) {
            return Optional.empty();
        }
        Matcher matcher = TRACEPARENT.matcher(traceparent.strip());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String version = matcher.group(1);
        String traceId = matcher.group(2);
        String spanId = matcher.group(3);
        boolean extraFields = matcher.group(5) != null;
        if (version.equals("ff") || version.equals("00") && extraFields
                || traceId.equals(INVALID_TRACE_ID) || spanId.equals(INVALID_SPAN_ID)) {
            return Optional.empty();
        }
        boolean sampled = (Integer.parseInt(matcher.group(4), 16) & 1) == 1;
        return Optional.of(tracer.traceContextBuilder().traceId(traceId).spanId(spanId).sampled(sampled).build());
    }
}
