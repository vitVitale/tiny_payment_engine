package com.tpe.fixturesvc.application;

import io.micrometer.tracing.Span;

public class TagsSpanWithNumber {

    void tag(Span span) {
        span.tag("tpe.saga.attempt", 2L);
    }
}
