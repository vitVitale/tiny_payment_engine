package com.tpe.systemtests.observability;

import static com.tpe.systemtests.harness.ObservabilityBackends.LOKI;
import static com.tpe.systemtests.harness.ObservabilityBackends.MIMIR;
import static com.tpe.systemtests.harness.ObservabilityBackends.TEMPO;
import static com.tpe.systemtests.harness.ObservabilityBackends.backend;
import static com.tpe.systemtests.harness.ObservabilityBackends.backendJson;
import static com.tpe.systemtests.harness.ObservabilityBackends.encode;
import static com.tpe.systemtests.harness.ObservabilityBackends.ok;
import static com.tpe.systemtests.harness.ObservabilityBackends.send;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.observability.config.SloBoundaries;
import com.tpe.observability.test.PrometheusScrape;
import com.tpe.systemtests.harness.ObservabilityBackends;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OBS-AC-02 … -04 in their M0 form (specs/26-observability.md §15, issue #19) against
 * {@code docker compose -f deploy/docker-compose.yml --profile observability up -d --wait}: one request to the sample
 * workload, then its metrics, logs and trace in Mimir, Loki and Tempo, and the Grafana Tempo → Loki correlation.
 * OBS-AC-01 checks a real payment ({@link PaymentObservabilityIT}, issue #126).
 */
@EnabledIfSystemProperty(named = "tpe.observability-stack", matches = "true",
        disabledReason = "needs the compose observability profile; run with -Dtpe.observability-stack=true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ObservabilityPipelineIT {

    private static final String SAMPLE = "http://localhost:8095";
    private static final String GRAFANA = "http://localhost:3000";
    private static final String APPLICATION = "observability-sample";
    private static final String LATENCY_BUCKETS = "http_server_requests_seconds_bucket";
    private static final String OPEN_METRICS = "application/openmetrics-text; version=1.0.0";
    /**
     * The labels the pipeline adds (§3: target labels and external labels), as deploy/docker-compose.yml sets them:
     * the compose project, the sample's container name, TPE_ENV and TPE_CLUSTER of the agent.
     */
    private static final Map<String, String> PIPELINE_LABELS = Map.of("namespace", "tpe",
            "pod", "tpe-observability-sample-1", "instance", "tpe-observability-sample-1", "env", "local",
            "cluster", "compose");
    /** OBS-AC-01's bound (§15), which the later checks keep. */
    private static final Duration WITHIN = Duration.ofSeconds(60);
    /** The window Grafana's Tempo → Loki correlation searches around a span (§7). */
    private static final Duration CORRELATION_SHIFT = Duration.ofMinutes(5);

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Clock CLOCK = Clock.systemUTC();

    private static Instant requested;
    private static String traceId;
    private static String paymentId;

    @BeforeAll
    static void handleASamplePayment() {
        await().atMost(ObservabilityBackends.MIMIR_STARTUP).ignoreExceptions().until(ObservabilityBackends::mimirReady);
        requested = CLOCK.instant();
        JsonNode handled = JSON.readTree(send(HttpRequest.newBuilder(URI.create(SAMPLE + "/sample/payments"))
                .POST(HttpRequest.BodyPublishers.noBody()).build()).body());
        traceId = handled.get("traceId").asString();
        paymentId = handled.get("paymentId").asString();
    }

    @Test
    @Order(2)
    void OBS_AC_02_everyScrapedSeriesIsInMimirWithTheSameNameAndLabels() {
        await().atMost(WITHIN).ignoreExceptions()
                .until(() -> mimirSeries(sampleSeries()).containsAll(scrapedSeries()));

        // no label beyond the app's own and §3 (the app's pass the contract test's allow-list); by label names, as
        // Mimir also holds series of earlier sample containers
        Map<String, Set<String>> scrapedLabels = labelNamesByMetric(scrapedSeries());
        assertThat(labelNamesByMetric(mimirSeries(sampleSeries()))).allSatisfy((metric, labels) ->
                assertThat(labels).as(metric).isEqualTo(scrapedLabels.get(metric)));
        assertThat(mimirSeries("{__name__=~\".+\", application=\"\"}"))
                .as("series without an application: the pipeline's own, §3 labels only")
                .allSatisfy(series -> assertThat(series.keySet()).isSubsetOf(withName(PIPELINE_LABELS.keySet())));
        Set<Double> buckets = mimirSeries(LATENCY_BUCKETS + "{application=\"" + APPLICATION
                + "\",uri=\"/sample/payments\"}").stream().map(series -> series.get("le")).filter(le -> !le.equals("+Inf"))
                .map(Double::parseDouble)
                .collect(Collectors.toSet());
        assertThat(buckets).as("a bucket at every SLO threshold (25 §11)").containsAll(
                SloBoundaries.DEFAULTS.get("http.server.requests").stream().map(b -> b.toNanos() / 1e9).toList());
    }

    @Test
    @Order(3)
    void OBS_AC_03_aLatencyExemplarResolvesInTempo() {
        await().atMost(WITHIN).ignoreExceptions().until(() -> exemplarTraceIds().contains(traceId));

        assertThat(traceFound()).isTrue();
    }

    @Test
    @Order(4)
    void OBS_AC_04_theLogLineIsInLokiAndTheTempoToLokiCorrelationFindsIt() {
        await().atMost(WITHIN).ignoreExceptions().until(() -> !logStreams(LOKI, traceLogQuery()).isEmpty());
        JsonNode stream = logStreams(LOKI, traceLogQuery()).get(0).get("stream");
        assertThat(stream.get("trace_id").asString()).isEqualTo(traceId);
        assertThat(stream.get("payment_id").asString()).isEqualTo(paymentId);

        JsonNode found = logStreams(GRAFANA + "/api/datasources/proxy/uid/loki", correlationQuery());
        assertThat(found).isNotEmpty();
        assertThat(found.get(0).get("stream").get("payment_id").asString()).isEqualTo(paymentId);
    }

    /** The LogQL query Grafana runs from a span of this trace: its provisioned template, with the span's tags. */
    private static String correlationQuery() {
        JsonNode tracesToLogs = viaGrafana("/api/datasources/uid/tempo").get("jsonData").get("tracesToLogsV2");
        String tags = StreamSupport.stream(tracesToLogs.get("tags").spliterator(), false)
                .map(tag -> tag.get("value").asString() + "=\"" + resourceAttribute(tag.get("key").asString()) + "\"")
                .collect(Collectors.joining(","));
        return tracesToLogs.get("query").asString().replace("${__tags}", tags).replace("${__trace.traceId}", traceId);
    }

    private static String traceLogQuery() {
        return "{service_name=\"" + APPLICATION + "\"} | trace_id=\"" + traceId + "\"";
    }

    private static String sampleSeries() {
        return "{application=\"" + APPLICATION + "\"}";
    }

    private static boolean traceFound() {
        return backend(TEMPO + "/api/traces/" + traceId).statusCode() == 200;
    }

    /** {@code service.name} etc. of the trace's resource in Tempo. */
    private static String resourceAttribute(String key) {
        for (JsonNode batch : JSON.readTree(backend(TEMPO + "/api/traces/" + traceId).body()).get("batches")) {
            for (JsonNode attribute : batch.get("resource").get("attributes")) {
                if (attribute.get("key").asString().equals(key)) {
                    return attribute.get("value").get("stringValue").asString();
                }
            }
        }
        throw new AssertionError("trace " + traceId + " has no resource attribute " + key);
    }

    /** The sample's own scrape as the agent reads it (OpenMetrics), each series with the labels the pipeline adds. */
    private static Set<Map<String, String>> scrapedSeries() {
        String text = send(HttpRequest.newBuilder(URI.create(SAMPLE + "/actuator/prometheus"))
                .header("Accept", OPEN_METRICS).build()).body();
        return PrometheusScrape.parse(text).series().stream().map(series -> {
            Map<String, String> labels = new TreeMap<>(series.labels());
            labels.put("__name__", series.name());
            labels.putAll(PIPELINE_LABELS);
            return (Map<String, String>) labels;
        }).collect(Collectors.toSet());
    }

    private static Set<Map<String, String>> mimirSeries(String selector) {
        JsonNode data = backendJson(MIMIR + "/prometheus/api/v1/series?match[]=" + encode(selector)
                + "&start=" + since().getEpochSecond()).get("data");
        Set<Map<String, String>> series = new HashSet<>();
        for (JsonNode labels : data) {
            Map<String, String> map = new TreeMap<>();
            labels.properties().forEach(entry -> map.put(entry.getKey(), entry.getValue().asString()));
            series.add(map);
        }
        return series;
    }

    /** A sample of this run in Mimir, not only an exemplar: the request count of the endpoint. */
    private static double requestCountInMimir() {
        JsonNode result = backendJson(MIMIR + "/prometheus/api/v1/query?query=" + encode(
                "sum(http_server_requests_seconds_count{application=\"" + APPLICATION + "\",uri=\"/sample/payments\"})"))
                .get("data").get("result");
        return result.isEmpty() ? 0 : Double.parseDouble(result.get(0).get("value").get(1).asString());
    }

    private static Set<String> exemplarTraceIds() {
        return ObservabilityBackends.exemplarTraceIds(LATENCY_BUCKETS + "{application=\"" + APPLICATION
                + "\",uri=\"/sample/payments\"}", requested.minusSeconds(60), CLOCK.instant().plusSeconds(60));
    }

    /** The streams of a LogQL query around the run, at Loki or through Grafana's Loki data source. */
    private static JsonNode logStreams(String loki, String query) {
        String url = loki + "/loki/api/v1/query_range?query=" + encode(query)
                + "&start=" + since().toEpochMilli() * 1_000_000
                + "&end=" + CLOCK.instant().plus(CORRELATION_SHIFT).toEpochMilli() * 1_000_000;
        return (loki.startsWith(GRAFANA) ? viaGrafana(url.substring(GRAFANA.length())) : backendJson(url))
                .get("data").get("result");
    }

    private static Instant since() {
        return requested.minus(CORRELATION_SHIFT);
    }

    /** Metric name → the union of its series' label names. */
    private static Map<String, Set<String>> labelNamesByMetric(Set<Map<String, String>> series) {
        return series.stream().collect(Collectors.groupingBy(labels -> labels.get("__name__"),
                Collectors.flatMapping(labels -> labels.keySet().stream(), Collectors.toSet())));
    }

    private static Set<String> withName(Set<String> labels) {
        Set<String> names = new HashSet<>(labels);
        names.add("__name__");
        return names;
    }

    /** Grafana's API; its data sources add the tenant header. */
    private static JsonNode viaGrafana(String path) {
        String url = GRAFANA + path;
        return ok(url, send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).build()));
    }
}
