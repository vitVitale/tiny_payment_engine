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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Reference check p99 ≤ 50 ms (specs/13-vop-service.md §8): it runs inside payment-api's 300 ms initiation budget.
 * Component level on the CI Testcontainers setup, an open load of 200 checks/s, measured from the scheduled start.
 */
class ReferenceCheckLatencyIT extends VopStack {

    private static final int RATE = 200;
    private static final Duration RUN = Duration.ofSeconds(10);
    private static final Duration WARM_UP = Duration.ofSeconds(3);
    private static final Duration TARGET = Duration.ofMillis(50);
    private static final String DEBTOR = "DE36100100100000000001";

    @Autowired
    AccountHolderProjection projection;

    private final HttpClient client = HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @Test
    void referenceCheckP99IsWithin50msAt200ChecksPerSecond() throws Exception {
        List<String[]> checks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String payee = holder();
            checks.add(new String[] {issue(payee), payee, i % 5 == 0 ? "Erika Mustermann" : "Max Mustermann"});
        }
        // a share of made-up references, answered UNKNOWN
        for (int i = 0; i < 10; i++) {
            checks.add(new String[] {"VOP-" + UUID.randomUUID(), DEBTOR, "Max Mustermann"});
        }
        run(checks, WARM_UP);

        long[] latencies = run(checks, RUN);

        Arrays.sort(latencies);
        long p99 = latencies[(int) Math.ceil(latencies.length * 0.99) - 1];
        assertThat(Duration.ofNanos(p99)).as("p99 of %d checks (max %d ms)", latencies.length,
                TimeUnit.NANOSECONDS.toMillis(latencies[latencies.length - 1])).isLessThanOrEqualTo(TARGET);
    }

    /** Latencies in nanoseconds of {@code RATE × duration} checks; every one must answer {@code 200}. */
    private long[] run(List<String[]> checks, Duration duration) throws Exception {
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
            String[] check = checks.get(i % checks.size());
            String body = """
                    {"debtorIban":"%s","payeeIban":"%s","payeeName":"%s"}""".formatted(DEBTOR, check[1], check[2]);
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/v1/verifications/" + check[0] + "/check"))
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

    private String holder() {
        var holder = new AccountHolder(UUID.randomUUID(), Ibans.own(), HolderType.PERSON, "Max Mustermann", null,
                AccountStatus.OPEN, 1);
        projection.apply(UUID.randomUUID(), holder);
        return holder.iban();
    }

    private String issue(String payee) {
        JsonNode body = http().post().uri("/v1/verifications").header("Content-Type", "application/json")
                .body(Map.of("debtorIban", DEBTOR, "payeeIban", payee, "payeeName", "Max Mustermann"))
                .retrieve().body(JsonNode.class);
        return body.get("vopReference").asString();
    }
}
