package com.tpe.observability.export;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Puts a bound in front of the batch span processor (specs/26-observability.md §4.2): once {@code capacity} sampled
 * spans wait for export, further spans are dropped and counted in {@link DroppedSpans}. A full queue or an
 * unreachable collector therefore never blocks the caller. The batch processor must export through the
 * {@link CountingSpanExporter} given here, which frees a place per exported span. An exporter must complete every
 * export, as the OTLP exporters do after their timeout and retries; one that never does keeps its places.
 */
public final class DropOnFullSpanProcessor implements SpanProcessor {

    private final SpanProcessor batch;
    private final int capacity;
    private final boolean exportsUnsampled;
    private final DroppedSpans dropped;
    private final AtomicInteger pending;

    /**
     * @param batch the batch span processor, exporting through {@code exporter}
     * @param exporter the counting exporter that {@code batch} exports through
     * @param capacity at most this many spans wait for export; the batch processor's queue size
     * @param exportsUnsampled whether {@code batch} exports unsampled spans too, so they take a place as well
     */
    public DropOnFullSpanProcessor(BatchSpanProcessor batch, CountingSpanExporter exporter, int capacity,
            boolean exportsUnsampled, DroppedSpans dropped) {
        this.batch = batch;
        this.capacity = capacity;
        this.exportsUnsampled = exportsUnsampled;
        this.dropped = dropped;
        this.pending = exporter.pending;
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        batch.onStart(parentContext, span);
    }

    @Override
    public boolean isStartRequired() {
        return batch.isStartRequired();
    }

    @Override
    public void onEnd(ReadableSpan span) {
        if (!span.getSpanContext().isSampled() && !exportsUnsampled) {
            return; // the batch processor would not export it either
        }
        if (pending.incrementAndGet() > capacity) {
            pending.decrementAndGet();
            dropped.add(1);
            return;
        }
        batch.onEnd(span);
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    @Override
    public CompletableResultCode forceFlush() {
        return batch.forceFlush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return batch.shutdown();
    }
}
