package com.tpe.fixturesvc.infrastructure;

import io.micrometer.tracing.Span;

public class OpenTelemetryAdapter {

    void tag(Span span) {
        span.tag("messaging.destination.name", "csm.outbound.commands");
        io.opentelemetry.api.trace.Span.current().setAttribute("messaging.system", "kafka");
    }
}
