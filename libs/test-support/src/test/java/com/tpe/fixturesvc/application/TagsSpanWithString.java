package com.tpe.fixturesvc.application;

import io.micrometer.tracing.Span;

public class TagsSpanWithString {

    void tag(Span span, String debtorName) {
        span.tag("debtor.name", debtorName);
    }
}
