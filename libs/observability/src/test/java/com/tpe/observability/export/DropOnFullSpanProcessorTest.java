package com.tpe.observability.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DropOnFullSpanProcessorTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DroppedSpans dropped = new DroppedSpans();
    private final CountDownLatch release = new CountDownLatch(1);
    private SdkTracerProvider provider;

    @AfterEach
    void shutdown() {
        release.countDown();
        if (provider != null) {
            provider.shutdown().join(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void dropsAndCountsWhenTheQueueIsFullWithoutBlocking() {
        Tracer tracer = tracerExportingTo(new BlockingExporter(release), 4);

        long start = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            tracer.spanBuilder("span-" + i).startSpan().end();
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(Duration.ofMillis(500));
        assertThat(dropped.count()).isEqualTo(16);
        assertThat(registry.get("tpe.telemetry.spans.dropped").functionCounter().count()).isEqualTo(16);
    }

    @Test
    void failedExportsAreCountedAsDropped() {
        Tracer tracer = tracerExportingTo(new FailingExporter(), 100);

        for (int i = 0; i < 3; i++) {
            tracer.spanBuilder("span-" + i).startSpan().end();
        }
        provider.forceFlush().join(5, TimeUnit.SECONDS);

        assertThat(dropped.count()).isEqualTo(3);
    }

    @Test
    void exportedSpansFreeTheirPlace() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        Tracer tracer = tracerExportingTo(exporter, 2);

        for (int round = 1; round <= 3; round++) {
            tracer.spanBuilder("a").startSpan().end();
            tracer.spanBuilder("b").startSpan().end();
            // forceFlush may return while the worker still exports a batch it took on its own: wait for the export
            int exported = 2 * round;
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> exporter.getFinishedSpanItems().size() >= exported || dropped.count() > 0);
            assertThat(dropped.count()).as("dropped in round %d", round).isZero();
        }

        assertThat(exporter.getFinishedSpanItems()).hasSize(6);
    }

    private Tracer tracerExportingTo(SpanExporter exporter, int capacity) {
        dropped.bindTo(registry);
        CountingSpanExporter counting = new CountingSpanExporter(exporter, dropped);
        BatchSpanProcessor batch = BatchSpanProcessor.builder(counting)
                .setMaxQueueSize(capacity)
                .setMaxExportBatchSize(capacity)
                .setScheduleDelay(Duration.ofMillis(10))
                .build();
        provider = SdkTracerProvider.builder()
                .addSpanProcessor(new DropOnFullSpanProcessor(batch, counting, capacity, false, dropped))
                .build();
        return provider.get("test");
    }

    /** Never completes an export until released: the collector is unreachable or slow. */
    private record BlockingExporter(CountDownLatch release) implements SpanExporter {

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            CompletableResultCode result = new CompletableResultCode();
            Thread.ofVirtual().start(() -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                result.succeed();
            });
            return result;
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    private static final class FailingExporter implements SpanExporter {

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            return CompletableResultCode.ofFailure();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
