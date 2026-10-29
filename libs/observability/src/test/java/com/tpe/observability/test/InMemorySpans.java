package com.tpe.observability.test;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The spans a component test produced, from an in-memory exporter next to the real one (specs/26-observability.md
 * §4, package {@code test}). {@code @Import(InMemorySpans.Configuration.class)}, then assert on the {@link SpanData}
 * with {@code io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions}. The spans go through the same bounded
 * export as in production.
 */
public final class InMemorySpans {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private final InMemorySpanExporter exporter;
    private final SdkTracerProvider provider;

    InMemorySpans(InMemorySpanExporter exporter, SdkTracerProvider provider) {
        this.exporter = exporter;
        this.provider = provider;
    }

    /** Every span ended and exported so far. */
    public List<SpanData> finished() {
        provider.forceFlush().join(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        return exporter.getFinishedSpanItems();
    }

    /**
     * The first finished span matching {@code span}, waiting up to 5 s: a server span ends after the response has
     * reached the client.
     */
    public SpanData await(Predicate<SpanData> span) {
        Instant deadline = Instant.now(Clock.systemUTC()).plus(WAIT);
        do {
            var found = finished().stream().filter(span).findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            pause();
        } while (Instant.now(Clock.systemUTC()).isBefore(deadline));
        throw new AssertionError("No finished span matches within " + WAIT + "; finished: "
                + finished().stream().map(SpanData::getName).toList());
    }

    private static void pause() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Forgets the spans so far. */
    public void reset() {
        finished();
        exporter.reset();
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Configuration {

        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }

        @Bean
        InMemorySpans inMemorySpans(InMemorySpanExporter exporter, SdkTracerProvider provider) {
            return new InMemorySpans(exporter, provider);
        }
    }
}
