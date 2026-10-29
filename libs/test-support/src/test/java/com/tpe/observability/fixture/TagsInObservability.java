package com.tpe.observability.fixture;

import io.micrometer.tracing.Span;

/** Stands in for libs/observability, which may tag spans with strings (PaymentTelemetry). */
public class TagsInObservability {

    void tag(Span span) {
        span.tag("tpe.tx.id", "TPE01JAB3C4D5E6F7G8H9J0K1M2NP");
    }
}
