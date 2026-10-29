package com.tpe.observability.export;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts spans that were never exported: dropped because the export queue was full, or lost in a failed export
 * (specs/26-observability.md §4.2). Exposed as {@code tpe.telemetry.spans.dropped}; bound to the registry as a
 * {@link MeterBinder}, so span export does not depend on the meter registry.
 */
public final class DroppedSpans implements MeterBinder {

    static final String METER = "tpe.telemetry.spans.dropped";

    private final AtomicLong dropped = new AtomicLong();

    void add(long spans) {
        dropped.addAndGet(spans);
    }

    /** Spans dropped since start. */
    public long count() {
        return dropped.get();
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        FunctionCounter.builder(METER, dropped, AtomicLong::get)
                .description("Spans dropped because the export queue was full or the export failed")
                .register(registry); // no base unit: it would rename the series (26 §4.1, names are the contract)
    }
}
