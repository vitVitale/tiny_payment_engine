package com.tpe.paymentorchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.contracts.CreditTransferSettled;
import com.tpe.contracts.Direction;
import com.tpe.contracts.SettlementSource;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentorchestrator.SagaProbe.Row;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A payment the CSM has without an answer by the scheme deadline (specs/11-payment-orchestrator.md §6.4, UC-05): it
 * goes into investigation, asks for its status per {@code tpe.investigation.backoff} until the CSM answers or the
 * budget runs out, and keeps its hold meanwhile.
 */
class InvestigationIT extends OrchestratorStack {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration AWAIT = Duration.ofSeconds(15);
    /** Long enough for the listener, the runner, the sweeper and the timers to have acted many times. */
    private static final Duration QUIET = Duration.ofSeconds(1);
    /** Past {@code deadline_at}: 20 s after acceptance. */
    private static final Duration PAST_DEADLINE = Duration.ofSeconds(21);

    @Autowired
    JdbcClient jdbc;

    private SagaProbe probe;

    @BeforeEach
    void probe() {
        probe = new SagaProbe(jdbc, CLOCK, RISK, LEDGER);
        riskAnswers("allow");
        ledgerAnswers("create-hold-created", "settle-hold-settled", "release-released");
    }

    @Test
    void ORC_AC_09_aSentPaymentWithoutAnswerIsInvestigatedPerTheBackoffUntilTheBudgetRunsOut() {
        UUID paymentId = sent();
        String txId = probe.txId(paymentId);

        CLOCK.advance(PAST_DEADLINE);
        Instant started = CLOCK.instant();
        await().atMost(AWAIT).until(() -> statusRequests(txId).size() == 1);
        assertThat(probe.row(paymentId)).isEqualTo(new Row("INVESTIGATING", null, null, false));
        assertThat(probe.transitions(paymentId)).endsWith("SENT_TO_CSM|INVESTIGATING|DEADLINE");
        assertThat(statusRequests(txId)).singleElement().satisfies(request -> {
            assertThat(request.get("paymentId").asString()).isEqualTo(paymentId.toString());
            assertThat(request.get("direction").asString()).isEqualTo("OUTGOING");
            assertThat(request.get("debtorAgentBic").asString()).isEqualTo(debtorAgent(paymentId).value());
            assertThat(request.get("attempt").asInt()).isEqualTo(1);
            assertThat(Instant.parse(request.get("requestedAt").asString())).isEqualTo(started);
        });
        assertThat(jdbc.sql("SELECT investigation_started_at FROM payments WHERE payment_id = ?").param(paymentId)
                .query(Timestamp.class).single().toInstant()).isEqualTo(started);

        // retries at +5 s, +15 s, +60 s, then every 5 min
        List<Duration> delays = List.of(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(60),
                Duration.ofMinutes(5), Duration.ofMinutes(5));
        for (int i = 0; i < delays.size(); i++) {
            assertThat(scheduledRetry(paymentId)).isEqualTo(CLOCK.instant().plus(delays.get(i)));
            CLOCK.advance(delays.get(i));
            int requests = i + 2;
            await().atMost(AWAIT).until(() -> statusRequests(txId).size() == requests);
        }
        assertThat(statusRequests(txId)).extracting(request -> request.get("attempt").asInt())
                .as("strictly increasing").containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(probe.row(paymentId)).isEqualTo(new Row("INVESTIGATING", null, null, false));

        // every 5 min within the budget; the first retry past one hour opens the case instead of asking
        Instant budgetEnd = started.plus(Duration.ofHours(1));
        int sent = 6;
        while (!CLOCK.instant().plus(Duration.ofMinutes(5)).isAfter(budgetEnd)) {
            CLOCK.advance(Duration.ofMinutes(5));
            int requests = ++sent;
            await().atMost(AWAIT).until(() -> statusRequests(txId).size() == requests);
        }
        assertThat(probe.cases(paymentId)).as("within the budget").isEmpty();
        CLOCK.advance(Duration.ofMinutes(5));
        await().atMost(AWAIT).until(() -> !probe.cases(paymentId).isEmpty());
        assertThat(probe.cases(paymentId)).containsExactly("UNRESOLVED_INVESTIGATION|OPEN");
        assertThat(statusRequests(txId)).extracting(request -> request.get("attempt").asInt())
                .as("strictly increasing").isSorted().doesNotHaveDuplicates().hasSize(sent);
        assertThat(probe.timers(paymentId)).as("no further automatic retries")
                .doesNotContain("INVESTIGATION_RETRY|SCHEDULED");

        CLOCK.advance(Duration.ofMinutes(10));
        int sentBeforeCase = sent;
        await().during(QUIET).atMost(AWAIT).until(() -> statusRequests(txId).size() == sentBeforeCase);
        assertThat(probe.row(paymentId)).isEqualTo(new Row("INVESTIGATING", null, null, false));
        assertThat(probe.releaseCalls(paymentId)).as("the hold is never released meanwhile").isEmpty();
        assertThat(probe.finalEvents(paymentId)).isEmpty();
    }

    @Test
    void ORC_AC_24_aStatusReportDuringTheInvestigationSettlesOnceAndALateAnswerHasNoEffect() {
        UUID paymentId = sent();
        String txId = probe.txId(paymentId);
        CLOCK.advance(PAST_DEADLINE);
        await().atMost(AWAIT).until(() -> statusRequests(txId).size() == 1);

        publishCsmEvent(settled(paymentId, SettlementSource.STATUS_REPORT), txId, UUID.randomUUID());

        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId)).isEqualTo(
                new Row("SETTLED", null, null, true)));
        assertThat(probe.transitions(paymentId)).endsWith("SENT_TO_CSM|INVESTIGATING|DEADLINE",
                "INVESTIGATING|SETTLED|HOLD_SETTLED");
        assertThat(probe.timers(paymentId)).containsExactly("OUTGOING_DEADLINE|FIRED",
                "INVESTIGATION_RETRY|CANCELLED");

        publishCsmEvent(settled(paymentId, SettlementSource.INVESTIGATION), txId, UUID.randomUUID());
        CLOCK.advance(Duration.ofSeconds(5));

        await().during(QUIET).atMost(AWAIT).until(() -> probe.row(paymentId).equals(
                new Row("SETTLED", null, null, true)));
        assertThat(probe.settleCalls(paymentId)).hasSize(1);
        assertThat(probe.finalEvents(paymentId)).containsExactly("PaymentSettled|");
        assertThat(statusRequests(txId)).as("the cancelled retry asks no more").hasSize(1);
        assertThat(probe.cases(paymentId)).isEmpty();
    }

    /** The payment's {@code RequestPaymentStatus} commands, oldest first. */
    private List<JsonNode> statusRequests(String txId) {
        return probe.csmCommands(txId).stream()
                .filter(command -> command.startsWith("RequestPaymentStatus|"))
                .map(command -> JSON.readTree(command.substring(command.indexOf('|') + 1)))
                .toList();
    }

    private Instant scheduledRetry(UUID paymentId) {
        return jdbc.sql("""
                        SELECT due_at FROM payment_timers
                        WHERE payment_id = ? AND type = 'INVESTIGATION_RETRY' AND status = 'SCHEDULED'""")
                .param(paymentId).query(Timestamp.class).single().toInstant();
    }

    /** A payment to the peer bank, {@code SENT_TO_CSM}. */
    private UUID sent() {
        Map<String, Object> request = PaymentCommandIT.request(Ibans.own(), Ibans.peer());
        String body = http().post().uri("/v1/payments").contentType(MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsString(request)).retrieve().body(String.class);
        UUID paymentId = UUID.fromString(JSON.readTree(body).get("paymentId").asString());
        await().atMost(AWAIT).until(() -> "SENT_TO_CSM".equals(probe.row(paymentId).state()));
        return paymentId;
    }

    private CreditTransferSettled settled(UUID paymentId, SettlementSource source) {
        return new CreditTransferSettled(new TxId(probe.txId(paymentId)), debtorAgent(paymentId), Direction.OUTGOING,
                new PaymentId(paymentId), CLOCK.instant(), source);
    }

    private Bic debtorAgent(UUID paymentId) {
        return new Bic(jdbc.sql("SELECT debtor_agent_bic FROM payments WHERE payment_id = ?").param(paymentId)
                .query(String.class).single());
    }
}
