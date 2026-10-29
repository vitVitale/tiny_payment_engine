package com.tpe.observability.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.observability.export.DroppedSpans;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/** 26 §4.2: with the agent unreachable, requests are not slowed and the lost spans are counted. */
@SpringBootTest(
        classes = SampleApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.application.name=sample-service",
            // nothing listens on the discard port
            "OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:9",
            "tpe.observability.trace.export-timeout=300ms"
        })
class UnreachableCollectorTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    int port;

    @Autowired
    DroppedSpans dropped;

    @Autowired
    SdkTracerProvider provider;

    @Test
    void requestsAreNotSlowedAndDroppedSpansAreCounted() throws Exception {
        get("/sample"); // warm up
        Instant start = Instant.now(Clock.systemUTC());
        for (int i = 0; i < 20; i++) {
            assertThat(get("/sample").statusCode()).isEqualTo(200);
        }
        Duration elapsed = Duration.between(start, Instant.now(Clock.systemUTC()));
        provider.forceFlush().join(5, TimeUnit.SECONDS);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
        // the OTLP exporter retries with backoff; the spans count as dropped when it gives up
        awaitDroppedSpans(Duration.ofSeconds(30));
        assertThat(dropped.count()).isPositive();
        assertThat(get("/actuator/prometheus").body())
                .containsPattern("tpe_telemetry_spans_dropped_total\\{[^}]*} [1-9]");
    }

    private void awaitDroppedSpans(Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now(Clock.systemUTC()).plus(timeout);
        while (dropped.count() == 0 && Instant.now(Clock.systemUTC()).isBefore(deadline)) {
            Thread.sleep(100);
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "text/plain")
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
