package com.tpe.systemtests.observability;

import static com.tpe.systemtests.harness.ObservabilityBackends.exemplarTraceIds;
import static com.tpe.systemtests.harness.ObservabilityBackends.lokiStreams;
import static com.tpe.systemtests.harness.ObservabilityBackends.mimirValue;
import static com.tpe.systemtests.harness.ObservabilityBackends.serviceNames;
import static com.tpe.systemtests.harness.ObservabilityBackends.tempoTrace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.systemtests.harness.ObservabilityBackends;
import com.tpe.systemtests.harness.PaymentSystemTest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;

/**
 * OBS-AC-01 with a real outgoing payment (specs/26-observability.md §15, issue #126): the harness's services run as
 * host processes, which the local Alloy scrapes and tails through the harness's targets list and log files and whose
 * spans reach it on its OTLP port (§13). Within 60 s of the payment's {@code 202}: its log lines in Loki by
 * {@code payment_id}, their trace in Tempo across payment-api and payment-orchestrator, the trace's lines in Loki by
 * {@code trace_id}; its metrics in Mimir: the exemplar of its submit's latency is that trace, and the orchestrator's
 * count of ended payments has moved. The incoming payment joins with M4.
 */
@EnabledIfSystemProperty(named = "tpe.local-stack", matches = "true",
        disabledReason = "needs the compose stack; run with -Dtpe.local-stack=true")
@EnabledIfSystemProperty(named = "tpe.observability-stack", matches = "true",
        disabledReason = "needs the compose observability profile; run with -Dtpe.observability-stack=true")
class PaymentObservabilityIT extends PaymentSystemTest {

    /** OBS-AC-01. */
    private static final Duration WITHIN = Duration.ofSeconds(60);
    /** Where the queries start: the payment's lines and spans are not older than its {@code 202} by more than this. */
    private static final Duration BEFORE = Duration.ofMinutes(1);
    /** The scrape interval of the agent is 15 s; a query a second keeps the 60 s count tight. */
    private static final Duration POLL = Duration.ofSeconds(1);

    @Test
    void OBS_AC_01_mimirLokiAndTempoHoldARealOutgoingPaymentWithin60s() {
        await().atMost(ObservabilityBackends.MIMIR_STARTUP).ignoreExceptions().until(ObservabilityBackends::mimirReady);
        double outcomesBefore = outcomesInMimir();
        Party payer = customer(50_000);

        UUID payment = paidToPeer(payer, peerPayee("ACCEPT"), "OBS-AC-01");
        Instant accepted = Instant.now();

        var traceId = new AtomicReference<String>();
        await().atMost(Duration.between(Instant.now(), accepted.plus(WITHIN))).pollInterval(POLL)
                .ignoreExceptions().untilAsserted(() -> {
                    // the orchestrator's lines carry the payment's id (26 §4.3); payment-api logs none of its own
                    JsonNode byPayment = lokiStreams("{service_name=\"payment-orchestrator\"} | payment_id=\""
                            + payment + "\"", accepted.minus(BEFORE), Instant.now());
                    assertThat(byPayment).as("the orchestrator's lines of the payment in Loki").isNotEmpty();
                    traceId.set(byPayment.get(0).get("stream").get("trace_id").asString());
                    assertThat(lokiStreams("{service_namespace=\"tpe\"} | trace_id=\"" + traceId.get() + "\"",
                            accepted.minus(BEFORE), Instant.now())).as("the trace's lines in Loki").isNotEmpty();
                    JsonNode trace = tempoTrace(traceId.get());
                    assertThat(trace).as("trace %s in Tempo", traceId.get()).isNotNull();
                    assertThat(serviceNames(trace)).contains("payment-api", "payment-orchestrator");
                    // its metrics: the latency sample of its submit, whose exemplar is its trace (ADR-013)
                    assertThat(exemplarTraceIds("http_server_requests_seconds_bucket{application=\"payment-api\","
                            + "uri=\"/v1/payments\",method=\"POST\"}", accepted.minus(BEFORE), Instant.now()))
                            .as("payment-api's submit in Mimir, its exemplar the trace").contains(traceId.get());
                    assertThat(outcomesInMimir()).as("the orchestrator's outcomes in Mimir")
                            .isGreaterThan(outcomesBefore);
                });
        assertThat(finalStatus(payer, payment).get("status").asString()).isEqualTo("ACSC");
    }

    /** The orchestrator's payments ended, as Mimir has them now. */
    private static double outcomesInMimir() {
        return mimirValue("sum(tpe_payment_duration_seconds_count{application=\"payment-orchestrator\"})");
    }
}
