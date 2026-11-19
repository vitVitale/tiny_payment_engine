package com.tpe.systemtests.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mimir, Loki and Tempo of the compose profile {@code observability} (specs/26-observability.md §13), queried as tenant
 * {@code tpe-local} the way Grafana's data sources do: what OBS-AC-01 … -04 read.
 */
public final class ObservabilityBackends {

    public static final String MIMIR = "http://localhost:9009";
    public static final String LOKI = "http://localhost:3100";
    public static final String TEMPO = "http://localhost:3200";
    public static final String TENANT = "tpe-local";

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** For Mimir to answer after {@code up --wait}: it has no compose healthcheck (distroless image). */
    public static final Duration MIMIR_STARTUP = Duration.ofSeconds(60);

    private ObservabilityBackends() {}

    /** Whether Mimir answers ({@link #MIMIR_STARTUP}). */
    public static boolean mimirReady() {
        return send(HttpRequest.newBuilder(URI.create(MIMIR + "/ready")).build()).statusCode() == 200;
    }

    /** The value of a PromQL query that sums to one series; 0 when it has none yet. */
    public static double mimirValue(String promQl) {
        JsonNode result = backendJson(MIMIR + "/prometheus/api/v1/query?query=" + encode(promQl))
                .get("data").get("result");
        return result.isEmpty() ? 0 : Double.parseDouble(result.get(0).get("value").get(1).asString());
    }

    /** The {@code trace_id} of every exemplar of the series {@code selector} selects, from {@code from} to {@code to}. */
    public static Set<String> exemplarTraceIds(String selector, Instant from, Instant to) {
        JsonNode data = backendJson(MIMIR + "/prometheus/api/v1/query_exemplars?query=" + encode(selector)
                + "&start=" + from.getEpochSecond() + "&end=" + to.getEpochSecond()).get("data");
        Set<String> ids = new HashSet<>();
        for (JsonNode series : data) {
            for (JsonNode exemplar : series.get("exemplars")) {
                JsonNode id = exemplar.get("labels").get("trace_id");
                if (id != null) {
                    ids.add(id.asString());
                }
            }
        }
        return ids;
    }

    /** The streams of a LogQL query between {@code from} and {@code to}. */
    public static JsonNode lokiStreams(String logQl, Instant from, Instant to) {
        return backendJson(LOKI + "/loki/api/v1/query_range?query=" + encode(logQl)
                + "&start=" + from.toEpochMilli() * 1_000_000 + "&end=" + to.toEpochMilli() * 1_000_000)
                .get("data").get("result");
    }

    /** The trace in Tempo; {@code null} while it is not there. */
    public static JsonNode tempoTrace(String traceId) {
        HttpResponse<String> response = backend(TEMPO + "/api/traces/" + traceId);
        return response.statusCode() == 200 ? JSON.readTree(response.body()) : null;
    }

    /** The {@code service.name} of every resource of a trace from {@link #tempoTrace}. */
    public static Set<String> serviceNames(JsonNode trace) {
        Set<String> names = new HashSet<>();
        for (JsonNode batch : trace.get("batches")) {
            String name = serviceName(batch);
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    /** The service and kind ({@code SPAN_KIND_SERVER}, …) of a trace's root span. */
    public record RootSpan(String service, String kind) {}

    /** The span without a parent of a trace from {@link #tempoTrace}; {@code null} if it has none. */
    public static RootSpan rootSpan(JsonNode trace) {
        for (JsonNode batch : trace.get("batches")) {
            for (JsonNode scope : batch.get("scopeSpans")) {
                for (JsonNode span : scope.get("spans")) {
                    JsonNode parent = span.get("parentSpanId");
                    if (parent == null || parent.asString().isEmpty()) {
                        return new RootSpan(serviceName(batch), span.get("kind").asString());
                    }
                }
            }
        }
        return null;
    }

    /** The {@code service.name} of the resource of a trace's batch; {@code null} if it has none. */
    private static String serviceName(JsonNode batch) {
        for (JsonNode attribute : batch.get("resource").get("attributes")) {
            if (attribute.get("key").asString().equals("service.name")) {
                return attribute.get("value").get("stringValue").asString();
            }
        }
        return null;
    }

    /** A backend directly, as tenant {@link #TENANT}. */
    public static HttpResponse<String> backend(String url) {
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("X-Scope-OrgID", TENANT).build());
    }

    /** {@link #backend} answering {@code 200}, as JSON. */
    public static JsonNode backendJson(String url) {
        return ok(url, backend(url));
    }

    public static JsonNode ok(String url, HttpResponse<String> response) {
        assertThat(response.statusCode()).as("GET %s: %s", url, response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    public static HttpResponse<String> send(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(request.uri() + ": " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
