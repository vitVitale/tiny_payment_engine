package com.tpe.csmgateway;

import static com.tpe.csmgateway.IncomingPacs008.PEER_BIC;
import static com.tpe.csmgateway.IncomingPacs008.pacs008;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.CompilationMXBean;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Inbound {@code pacs.008} → {@code CreditTransferReceived} in the outbox ≤ 50 ms p99 (specs/15-csm-gateway.md §8).
 * The event is written in the endpoint's transaction, so the {@code 202} comes after it committed: each request's
 * round trip, from sending to the {@code 202}, bounds that latency from above. Component level on the CI
 * Testcontainers setup, one request at a time, as the CSM's single connection sends them.
 *
 * <p>The service is warmed up by rounds of {@link #ROUND} requests until {@link #QUIET_ROUNDS} rounds in a row meet the
 * target while the JIT is quiet (compiling for at most {@link #JIT_QUIET} of the round's time), at most
 * {@link #WARM_UP_ROUNDS} rounds, as {@code AssessmentLatencyIT} of risk-service does; the measured round is a fresh
 * one, every request of it counts, and the target is unchanged.
 */
class IncomingLatencyIT extends GatewayStack {

    private static final int ROUND = 500;
    private static final int MEASURED = 1_000;
    private static final int WARM_UP_ROUNDS = 10;
    private static final int QUIET_ROUNDS = 2;
    private static final double JIT_QUIET = 0.05;
    private static final Duration TARGET = Duration.ofMillis(50);
    private static final AtomicInteger TRANSFERS = new AtomicInteger();

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Test
    void anIncomingPacs008IsInTheOutboxWithin50msP99() throws Exception {
        CompilationMXBean jit = ManagementFactory.getCompilationMXBean();
        int rounds = 0;
        int quiet = 0;
        long warmUpP99;
        long warmUpJitMillis;
        long roundMillis;
        do {
            List<String> messages = messages(ROUND);
            long compiled = jit.getTotalCompilationTime();
            long started = System.nanoTime();
            warmUpP99 = p99(send(messages));
            roundMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            warmUpJitMillis = jit.getTotalCompilationTime() - compiled;
            rounds++;
            boolean settled = warmUpP99 <= TARGET.toNanos() && warmUpJitMillis <= roundMillis * JIT_QUIET;
            quiet = settled ? quiet + 1 : 0;
        } while (quiet < QUIET_ROUNDS && rounds < WARM_UP_ROUNDS);

        long[] latencies = send(messages(MEASURED));

        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE message_type = 'CreditTransferReceived'")
                .query(Integer.class).single()).isEqualTo(rounds * ROUND + MEASURED);
        assertThat(Duration.ofNanos(p99(latencies)))
                .as("p99 of %d pacs.008 (max %d ms) after %d warm-up rounds of %d, the last with p99 %d ms and %d ms "
                                + "of JIT compilation", latencies.length,
                        TimeUnit.NANOSECONDS.toMillis(Arrays.stream(latencies).max().orElseThrow()), rounds, ROUND,
                        TimeUnit.NANOSECONDS.toMillis(warmUpP99), warmUpJitMillis)
                .isLessThanOrEqualTo(TARGET);
    }

    /** New transfers, built before the clock starts: building and validating them is the CSM's work, not ours. */
    private static List<String> messages(int count) {
        List<String> messages = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int n = TRANSFERS.incrementAndGet();
            messages.add(pacs008("PEERM-LAT-" + n, "PEER-LAT-" + n, PEER_BIC, START));
        }
        return messages;
    }

    /** Each message's round trip in nanoseconds, one after another. */
    private long[] send(List<String> messages) throws Exception {
        URI inbound = URI.create("http://localhost:" + port + "/csm/v1/inbound");
        long[] latencies = new long[messages.size()];
        for (int i = 0; i < messages.size(); i++) {
            HttpRequest request = HttpRequest.newBuilder(inbound).header("Content-Type", "application/xml")
                    .POST(HttpRequest.BodyPublishers.ofString(messages.get(i))).build();
            long started = System.nanoTime();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            latencies[i] = System.nanoTime() - started;
            assertThat(response.statusCode()).isEqualTo(202);
        }
        return latencies;
    }

    private static long p99(long[] latencies) {
        long[] sorted = latencies.clone();
        Arrays.sort(sorted);
        return sorted[(int) Math.ceil(sorted.length * 0.99) - 1];
    }
}
