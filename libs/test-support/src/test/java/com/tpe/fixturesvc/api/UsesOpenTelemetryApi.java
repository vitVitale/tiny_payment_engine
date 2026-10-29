package com.tpe.fixturesvc.api;

import io.opentelemetry.api.trace.Span;

public class UsesOpenTelemetryApi {

    void tag(String payee) {
        Span.current().setAttribute("payee", payee);
    }
}
