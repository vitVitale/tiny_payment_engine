package com.tpe.vopservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.vopservice.application.accounts.AccountHolderProjection;
import com.tpe.vopservice.domain.accounts.AccountHolder;
import com.tpe.vopservice.domain.accounts.AccountHolder.AccountStatus;
import com.tpe.vopservice.domain.accounts.AccountHolder.HolderType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * VOP-AC-09: responder p99 ≤ 1 s at 200 requests/s (specs/13-vop-service.md §8, UC-03 NFR, SLO-VOP), component level
 * on the CI Testcontainers setup. An open load: requests start on schedule whether or not earlier ones have answered,
 * so a slow responder shows as latency instead of a lower rate. Latency is measured from the scheduled start.
 */
class ResponderLoadIT extends VopStack {

    private static final int RATE = 200;
    private static final Duration RUN = Duration.ofSeconds(15);
    private static final Duration WARM_UP = Duration.ofSeconds(3);
    private static final Duration TARGET = Duration.ofSeconds(1);
    private static final String[] NAMES = {
            "Anna Schmidt", "Schmidt Anna", "Anna Schmitt", "Peter Wagner", "Dr. Anna Schmidt-Berg"};

    @Autowired
    AccountHolderProjection projection;

    private final HttpClient client = HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @Test
    void VOP_AC_09_responderP99IsWithinOneSecondAt200RequestsPerSecond() throws Exception {
        List<String> ibans = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            var holder = new AccountHolder(UUID.randomUUID(), Ibans.own(), HolderType.PERSON, "Anna Schmidt-Berg",
                    null, i % 10 == 0 ? AccountStatus.BLOCKED : AccountStatus.OPEN, 1);
            projection.apply(UUID.randomUUID(), holder);
            ibans.add(holder.iban());
        }
        // a share of unknown IBANs, answered NOT_POSSIBLE
        for (int i = 0; i < 20; i++) {
            ibans.add(Ibans.own());
        }
        run(ibans, WARM_UP);

        long[] latencies = run(ibans, RUN);

        Arrays.sort(latencies);
        long p99 = latencies[(int) Math.ceil(latencies.length * 0.99) - 1];
        assertThat(latencies).hasSize(RATE * (int) RUN.toSeconds());
        assertThat(Duration.ofNanos(p99)).as("p99 of %d requests (max %d ms)", latencies.length,
                TimeUnit.NANOSECONDS.toMillis(latencies[latencies.length - 1])).isLessThanOrEqualTo(TARGET);
    }

    /** Latencies in nanoseconds of {@code RATE × duration} requests; every one must answer {@code 200}. */
    private long[] run(List<String> ibans, Duration duration) throws Exception {
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
            String body = """
                    {"requestId":"%s","requestingPspBic":"PEERDEFFXXX","iban":"%s","name":"%s"}"""
                    .formatted(UUID.randomUUID(), ibans.get(i % ibans.size()), NAMES[i % NAMES.length]);
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/vop/v1/verifications"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
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
