package com.tpe.observability.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class TraceContextCodecTest {

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private final TraceContextCodec codec = new TraceContextCodec(new SimpleTracer());

    @Test
    void roundTripsATraceparent() {
        TraceContext context = codec.decode(TRACEPARENT).orElseThrow();

        assertThat(context.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(context.spanId()).isEqualTo("00f067aa0ba902b7");
        assertThat(context.sampled()).isTrue();
        assertThat(codec.encode(context)).isEqualTo(TRACEPARENT);
    }

    @Test
    void keepsTheUnsampledFlag() {
        String unsampled = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00";

        assertThat(codec.encode(codec.decode(unsampled).orElseThrow())).isEqualTo(unsampled);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "garbage",
        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7",
        "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
        "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",
        "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01",
        "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
        "00-4bf92f3577b34da6a3ce929d0e0e473-00f067aa0ba902b7-01"
    })
    void rejectsInvalidTraceparents(String traceparent) {
        assertThat(codec.decode(traceparent)).isEmpty();
    }

    @Test
    void acceptsAFutureVersionWithExtraFields() {
        // W3C trace-context §4.3: a higher version is parsed as version 00, ignoring what follows
        assertThat(codec.decode("01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-extra")).isPresent();
    }
}
