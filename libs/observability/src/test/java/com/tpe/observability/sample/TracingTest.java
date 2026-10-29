package com.tpe.observability.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.observability.context.Spans;
import com.tpe.observability.test.InMemorySpans;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(
        classes = SampleApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.application.name=sample-service",
            "tpe.observability.external-hosts=localhost",
            "tpe.observability.inbound.link-paths=/public/**",
            "tpe.observability.inbound.ignore-paths=/csm/**"
        })
@Import(InMemorySpans.Configuration.class)
class TracingTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-" + SPAN_ID + "-01";
    private static final AttributeKey<String> URI_TAG = AttributeKey.stringKey("uri");

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    int port;

    @Autowired
    InMemorySpans spans;

    @Autowired
    Spans spanFactory;

    @BeforeEach
    void reset() {
        spans.reset();
    }

    @Test
    void actuatorRequestsCreateNoSpans() throws Exception {
        get("/actuator/health", Map.of());
        get("/actuator/prometheus", Map.of());
        get("/sample", Map.of());

        spans.await(serverSpanFor("/sample"));
        assertThat(spans.finished()).noneMatch(span -> span.getAttributes().asMap().values().stream()
                .anyMatch(value -> String.valueOf(value).contains("/actuator")));
    }

    @Test
    void jdbcQuerySpanHasNoParameterValues() throws Exception {
        get("/sample/query", Map.of());

        SpanData query = spans.await(span -> span.getName().equals("query"));
        assertThat(query.getAttributes().asMap().values()).allSatisfy(value ->
                assertThat(String.valueOf(value)).doesNotContain(SampleApplication.SECRET_PARAMETER));
        assertThat(spans.finished()).noneMatch(span -> span.getName().equals("result-set"));
    }

    @Test
    void latencyHistogramCarriesTheTraceIdAsExemplar() throws Exception {
        get("/exemplar", Map.of("traceparent", TRACEPARENT));
        spans.await(serverSpanFor("/exemplar"));

        String openMetrics = get("/actuator/prometheus",
                Map.of("Accept", "application/openmetrics-text; version=1.0.0; charset=utf-8")).body();

        assertThat(openMetrics.lines()
                .filter(line -> line.startsWith("http_server_requests_seconds_bucket") && line.contains("/exemplar\""))
                .filter(line -> line.contains("trace_id=\"" + TRACE_ID + "\"")))
                .isNotEmpty();
    }

    @Test
    void childOfContinuesAndLinkedToLinksTheStoredTrace() {
        spanFactory.childOf("payment.timer", TRACEPARENT).start().end();
        spanFactory.linkedTo("process payments.events", TRACEPARENT).start().end();

        SpanData child = spans.await(span -> span.getName().equals("payment.timer"));
        SpanData linked = spans.await(span -> span.getName().equals("process payments.events"));
        assertThat(child.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(child.getParentSpanId()).isEqualTo(SPAN_ID);
        assertThat(linked.getTraceId()).isNotEqualTo(TRACE_ID);
        assertThat(linked.getParentSpanContext().isValid()).isFalse();
        assertThat(linked.getLinks()).extracting(link -> link.getSpanContext().getSpanId()).containsExactly(SPAN_ID);
    }

    @Test
    void callsToExternalHostsCarryNoTraceHeaders() throws Exception {
        assertThat(get("/call?host=localhost", Map.of()).body()).isEqualTo("none");
        assertThat(get("/call?host=127.0.0.1", Map.of()).body()).startsWith("00-");
    }

    @Test
    void foreignTraceparentOnACustomerChannelBecomesALink() throws Exception {
        get("/public/hello", Map.of("traceparent", TRACEPARENT));

        SpanData root = spans.await(serverSpanFor("/public/hello"));
        assertThat(root.getTraceId()).isNotEqualTo(TRACE_ID);
        assertThat(root.getParentSpanContext().isValid()).isFalse();
        assertThat(root.getLinks()).extracting(LinkData::getSpanContext)
                .anySatisfy(context -> {
                    assertThat(context.getTraceId()).isEqualTo(TRACE_ID);
                    assertThat(context.getSpanId()).isEqualTo(SPAN_ID);
                });
    }

    @Test
    void traceparentFromTheCsmIsIgnoredAndInternalCallsContinueTheTrace() throws Exception {
        get("/csm/v1/inbound", Map.of("traceparent", TRACEPARENT));
        get("/sample", Map.of("traceparent", TRACEPARENT));

        SpanData csm = spans.await(serverSpanFor("/csm/v1/inbound"));
        assertThat(csm.getTraceId()).isNotEqualTo(TRACE_ID);
        assertThat(csm.getLinks()).isEmpty();
        assertThat(spans.await(serverSpanFor("/sample")).getParentSpanId()).isEqualTo(SPAN_ID);
    }

    private static Predicate<SpanData> serverSpanFor(String uri) {
        return span -> uri.equals(span.getAttributes().get(URI_TAG));
    }

    private HttpResponse<String> get(String path, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
