package com.tpe.riskservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.CompilationMXBean;
import java.lang.management.ManagementFactory;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Assessment p99 ≤ 50 ms with 1 M rows in {@code assessments} (specs/14-risk-service.md §8, RSK-AC-04): every
 * outgoing assessment reads the payer's history for the velocity and new-payee rules. Component level on the CI
 * Testcontainers setup, an open load of 200 assessments/s, measured from the scheduled start.
 *
 * <p>The criterion is about a service in steady state with a table of that size, so the measured window starts only
 * once both have settled. The bulk load is vacuumed and checkpointed, so no autovacuum or WAL-triggered checkpoint of
 * it runs during the window. The service is warmed up first by {@link #WARM_UP_ASSESSMENTS} assessments as fast as it
 * answers, so the JIT sees its compile thresholds early, then by rounds of {@link #WARM_UP} of the open load until
 * {@link #QUIET_ROUNDS} rounds in a row meet the target while the JIT is quiet (compiling for at most
 * {@link #JIT_QUIET} of the round), at most {@link #WARM_UP_ROUNDS} rounds. One fixed round was not enough on a
 * shared CI runner: C2 compile bursts of up to a second, still running well after the first rounds met the target,
 * took a core from the service and the open load queued behind them. The measured window is a fresh one after the
 * warm-up, every assessment of it counts, and the target is unchanged.
 */
class AssessmentLatencyIT extends RiskStack {

    private static final int ROWS = 1_000_000;
    private static final int PAYERS = 50;
    private static final int RATE = 200;
    private static final Duration RUN = Duration.ofSeconds(10);
    private static final Duration WARM_UP = Duration.ofSeconds(3);
    private static final int WARM_UP_ASSESSMENTS = 20_000;
    private static final int WARM_UP_CONCURRENCY = 16;
    private static final int WARM_UP_ROUNDS = 20;
    private static final int QUIET_ROUNDS = 2;
    private static final double JIT_QUIET = 0.05;
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
        saturate(payers);
        CompilationMXBean jit = ManagementFactory.getCompilationMXBean();
        int rounds = 0;
        int quiet = 0;
        long warmUpP99;
        long warmUpJitMillis;
        do {
            long compiled = jit.getTotalCompilationTime();
            warmUpP99 = p99(run(payers, WARM_UP));
            warmUpJitMillis = jit.getTotalCompilationTime() - compiled;
            rounds++;
            boolean settled = warmUpP99 <= TARGET.toNanos() && warmUpJitMillis <= WARM_UP.toMillis() * JIT_QUIET;
            quiet = settled ? quiet + 1 : 0;
        } while (quiet < QUIET_ROUNDS && rounds < WARM_UP_ROUNDS);

        long[] latencies = run(payers, RUN);

        assertThat(Duration.ofNanos(p99(latencies)))
                .as("p99 of %d assessments (max %d ms) after %d warm-up rounds of %s, the last with p99 %d ms and "
                                + "%d ms of JIT compilation", latencies.length,
                        TimeUnit.NANOSECONDS.toMillis(Arrays.stream(latencies).max().orElseThrow()), rounds, WARM_UP,
                        TimeUnit.NANOSECONDS.toMillis(warmUpP99), warmUpJitMillis)
                .isLessThanOrEqualTo(TARGET);
    }

    private static long p99(long[] latencies) {
        long[] sorted = latencies.clone();
        Arrays.sort(sorted);
        return sorted[(int) Math.ceil(sorted.length * 0.99) - 1];
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
        // what autovacuum would otherwise do to the load at a random moment of the measured window
        jdbc.sql("VACUUM (ANALYZE) assessments").update();
        jdbc.sql("CHECKPOINT").update();
    }

    /** {@link #WARM_UP_ASSESSMENTS} assessments, {@link #WARM_UP_CONCURRENCY} at a time; latencies are not kept. */
    private void saturate(List<String> payers) throws Exception {
        var permits = new Semaphore(WARM_UP_CONCURRENCY);
        var futures = new ArrayList<CompletableFuture<?>>(WARM_UP_ASSESSMENTS);
        for (int i = 0; i < WARM_UP_ASSESSMENTS; i++) {
            permits.acquire();
            futures.add(client.sendAsync(request(payers, i), HttpResponse.BodyHandlers.ofString())
                    .whenComplete((response, failure) -> permits.release()));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(5, TimeUnit.MINUTES);
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
            var sent = client.sendAsync(request(payers, i), HttpResponse.BodyHandlers.ofString());
            futures.add(sent.thenApply(response -> {
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

    /** The {@code i}-th outgoing assessment of the load: a third goes to a new payee without a VoP match. */
    private HttpRequest request(List<String> payers, int i) {
        Map<String, Object> body = AssessmentIT.request(UUID.randomUUID(), "OUTGOING", 600_000);
        body.put("debtor", Map.of("name", "Anna Schmidt", "iban", payers.get(i % payers.size())));
        body.put("vopResult", i % 3 == 0 ? "NO_MATCH" : "MATCH");
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/risk/assessments"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
    }
}
