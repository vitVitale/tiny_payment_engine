package com.tpe.observability.export;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wraps the exporters of the batch span processor: when an export completes, its spans leave the queue of
 * {@link DropOnFullSpanProcessor}; when it fails or times out, they count as dropped.
 */
public final class CountingSpanExporter implements SpanExporter {

    private final SpanExporter delegate;
    private final DroppedSpans dropped;
    /** Spans handed to the batch processor and not yet exported; shared with {@link DropOnFullSpanProcessor}. */
    final AtomicInteger pending = new AtomicInteger();

    public CountingSpanExporter(SpanExporter delegate, DroppedSpans dropped) {
        this.delegate = delegate;
        this.dropped = dropped;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        CompletableResultCode result;
        try {
            result = delegate.export(spans);
        } catch (RuntimeException e) {
            result = CompletableResultCode.ofExceptionalFailure(e);
        }
        CompletableResultCode completed = result;
        completed.whenComplete(() -> {
            pending.addAndGet(-spans.size());
            if (!completed.isSuccess()) {
                dropped.add(spans.size());
            }
        });
        return completed;
    }

    @Override
    public CompletableResultCode flush() {
        return delegate.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }
}
