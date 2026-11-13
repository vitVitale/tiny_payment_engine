package com.tpe.riskservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Assessment p99 ≤ 50 ms with 1 M rows in {@code assessments} (specs/14-risk-service.md §8, RSK-AC-04): every
 * outgoing assessment reads the payer's history for the velocity and new-payee rules. Component level on the CI
 * Testcontainers setup, an open load of 200 assessments/s, measured from the scheduled start.
 */
class AssessmentLatencyIT extends RiskStack {

    private static final int ROWS = 1_000_000;
    private static final int PAYERS = 50;
    private static final int RATE = 200;
    private static final Duration RUN = Duration.ofSeconds(10);
    private static final Duration WARM_UP = Duration.ofSeconds(3);
    private static final Duration TARGET = Duration.ofMillis(50);

    @Autowired
    JdbcClient jdbc;

    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient client = HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @Test
    void RSK_AC_04_assessmentP99IsWithin50msWith1MRowsInAssessments() throws Exception {
        List<String> payers = new ArrayList<>();
        for (int i = 0; i < PAYERS; i++) {
            payers.add(Ibans.fresh());
        }
        seed(payers);
        run(payers, WARM_UP);

        long[] latencies = run(payers, RUN);

        Arrays.sort(latencies);
        long p99 = latencies[(int) Math.ceil(latencies.length * 0.99) - 1];
        assertThat(Duration.ofNanos(p99)).as("p99 of %d assessments (max %d ms)", latencies.length,
                TimeUnit.NANOSECONDS.toMillis(latencies[latencies.length - 1])).isLessThanOrEqualTo(TARGET);
    }

    /**
     * {@link #ROWS} assessments of 100 000 other payers over the last 30 days, and for each payer of the load 15 allowed
     * payments in the last 24 h, 5 of them in the last hour.
     */
    private void seed(List<String> payers) {
        Instant now = CLOCK.instant();
        jdbc.sql("""
                        INSERT INTO assessments (assessment_id, payment_id, direction, debtor_iban, creditor_iban,
                                                 amount_minor, decision, reason_code, rule_hits, assessed_at)
                        SELECT 'seed-' || n || ':OUTGOING', gen_random_uuid(), 'OUTGOING',
                               'XX00SEED' || lpad((n % 100000)::text, 10, '0'),
                               'XX00PAYEE' || lpad((n % 7919)::text, 10, '0'),
                               1000 + n % 500000,
                               CASE WHEN n % 20 = 0 THEN 'DENY' ELSE 'ALLOW' END,
                               CASE WHEN n % 20 = 0 THEN 'FR01' END,
                               '[]'::jsonb,
                               ?::timestamptz - (n % 2592000) * interval '1 second'
                        FROM generate_series(1, ?) AS n""")
                .param(Timestamp.from(now)).param(ROWS).update();
        for (String payer : payers) {
            for (int i = 0; i < 15; i++) {
                Duration age = i < 5 ? Duration.ofMinutes(10L * i + 1) : Duration.ofHours(i);
                jdbc.sql("""
                                INSERT INTO assessments (assessment_id, payment_id, direction, debtor_iban,
                                                         creditor_iban, amount_minor, decision, rule_hits, assessed_at)
                                VALUES (?, ?, 'OUTGOING', ?, ?, 100000, 'ALLOW', '[]'::jsonb, ?)""")
                        .param(UUID.randomUUID() + ":OUTGOING").param(UUID.randomUUID()).param(payer)
                        .param(Ibans.fresh()).param(Timestamp.from(now.minus(age))).update();
            }
        }
        jdbc.sql("ANALYZE assessments").update();
    }

    /**
     * Latencies in nanoseconds of {@code RATE × duration} outgoing assessments; every one must answer {@code 200}. A
     * third goes to a new payee above 5 000.00 EUR without a VoP match.
     */
    private long[] run(List<String> payers, Duration duration) throws Exception {
        int total = RATE * (int) duration.toSeconds();
        long intervalNanos = TimeUnit.SECONDS.toNanos(1) / RATE;
        var futures = new ArrayList<CompletableFuture<Long>>(total);
        long start = System.nanoTime();
        for (int i = 0; i < total; i++) {
            long scheduled = start + i * intervalNanos;
            long wait = scheduled - System.nanoTime();
            if (wait > 0) {
                TimeUnit.NANOSECONDS.sleep(wait);
            }
            Map<String, Object> body = AssessmentIT.request(UUID.randomUUID(), "OUTGOING", 600_000);
            body.put("debtor", Map.of("name", "Anna Schmidt", "iban", payers.get(i % payers.size())));
            body.put("vopResult", i % 3 == 0 ? "NO_MATCH" : "MATCH");
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/risk/assessments"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            futures.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
                long latency = System.nanoTime() - scheduled;
                if (response.statusCode() != 200) {
                    throw new IllegalStateException(response.statusCode() + " " + response.body());
                }
                return latency;
            }));
        }
        long[] latencies = new long[total];
        for (int i = 0; i < total; i++) {
            latencies[i] = futures.get(i).get(30, TimeUnit.SECONDS);
        }
        return latencies;
    }
}
