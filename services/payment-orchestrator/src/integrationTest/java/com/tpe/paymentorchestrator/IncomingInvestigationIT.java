package com.tpe.paymentorchestrator;

import static com.tpe.paymentorchestrator.IncomingPaymentIT.creditTransferReceived;
import static com.tpe.paymentorchestrator.IncomingPaymentIT.txId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.contracts.AnswerSent;
import com.tpe.contracts.AnswerStatus;
import com.tpe.contracts.CreditTransferSettled;
import com.tpe.contracts.DecidedBy;
import com.tpe.contracts.Direction;
import com.tpe.contracts.SettlementSource;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentorchestrator.SagaProbe.Row;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An accepted incoming payment whose settlement does not come (specs/11-payment-orchestrator.md §6.4, §3.2, UC-06 8b):
 * {@code SETTLEMENT_OVERDUE} after {@code tpe.incoming.settlement-overdue} (30 s) of the accepting answer puts it into
 * investigation with {@code RequestPaymentStatus(INCOMING)}; the investigation and its case work as for an outgoing
 * payment, ending in the credit confirmed or cancelled.
 */
class IncomingInvestigationIT extends OrchestratorStack {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration AWAIT = Duration.ofSeconds(15);
    /** Long enough for the listener, the runner, the sweeper and the timers to have acted many times. */
    private static final Duration QUIET = Duration.ofSeconds(1);
    private static final Duration PAST_OVERDUE = Duration.ofSeconds(31);
    private static final Duration BUDGET = Duration.ofHours(1);
    private static final Bic PEER = new Bic("PEERDEFFXXX");
    private static final String OPERATOR = "ops-ben";

    @Autowired
    JdbcClient jdbc;

    private SagaProbe probe;

    @BeforeEach
    void probe() {
        probe = new SagaProbe(jdbc, CLOCK, RISK, LEDGER);
        riskAnswers("allow");
        ledgerAnswers("create-pending-credit-created", "confirm-pending-credit-confirmed",
                "cancel-pending-credit-cancelled");
    }

    @Test
    void ORC_AC_17_noSettlementWithin30sOfTheAcceptingAnswerStartsAnIncomingInvestigation() {
        String txId = txId();
        UUID paymentId = accepted(txId);
        Instant answered = CLOCK.instant();
        assertThat(probe.timers(paymentId)).containsExactly("SETTLEMENT_OVERDUE|SCHEDULED");
        assertThat(dueAt(paymentId, "SETTLEMENT_OVERDUE")).isEqualTo(answered.plusSeconds(30));

        CLOCK.advance(PAST_OVERDUE);

        await().atMost(AWAIT).until(() -> statusRequests(txId).size() == 1);
        assertThat(probe.row(paymentId)).isEqualTo(new Row("INVESTIGATING", null, null, false));
        assertThat(probe.transitions(paymentId)).endsWith("ACCEPTED|INVESTIGATING|SETTLEMENT_OVERDUE");
        assertThat(statusRequests(txId)).singleElement().satisfies(request -> {
            assertThat(request.get("paymentId").asString()).isEqualTo(paymentId.toString());
            assertThat(request.get("direction").asString()).isEqualTo("INCOMING");
            assertThat(request.get("debtorAgentBic").asString()).isEqualTo(PEER.value());
            assertThat(request.get("attempt").asInt()).isEqualTo(1);
        });
        assertThat(probe.timers(paymentId)).containsExactly("SETTLEMENT_OVERDUE|FIRED",
                "INVESTIGATION_RETRY|SCHEDULED");
        assertThat(dueAt(paymentId, "INVESTIGATION_RETRY")).isEqualTo(CLOCK.instant().plusSeconds(5));

        CLOCK.advance(Duration.ofSeconds(5));
        await().atMost(AWAIT).until(() -> statusRequests(txId).size() == 2);
        assertThat(statusRequests(txId).get(1).get("attempt").asInt()).isEqualTo(2);
        assertThat(probe.confirmCreditCalls(paymentId)).isEmpty();
        assertThat(probe.cancelCreditCalls(paymentId)).as("the CSM may have settled").isEmpty();
    }

    @Test
    void aSettlementTheInvestigationFindsConfirmsTheCreditAndEndsTheRetries() {
        String txId = txId();
        UUID paymentId = investigating(txId);

        publishCsmEvent(new CreditTransferSettled(new TxId(txId), PEER, Direction.INCOMING, null, CLOCK.instant(),
                SettlementSource.INVESTIGATION), txId, UUID.randomUUID());

        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId))
                .isEqualTo(new Row("SETTLED", null, null, true)));
        assertThat(probe.transitions(paymentId)).endsWith("INVESTIGATING|SETTLED|CREDIT_CONFIRMED");
        assertThat(probe.confirmCreditCalls(paymentId)).hasSize(1);
        assertThat(probe.finalEvents(paymentId)).containsExactly("PaymentSettled|");
        assertThat(probe.timers(paymentId)).containsExactly("SETTLEMENT_OVERDUE|FIRED",
                "INVESTIGATION_RETRY|CANCELLED");
        CLOCK.advance(Duration.ofSeconds(5));
        await().during(QUIET).atMost(AWAIT).until(() -> statusRequests(txId).size() == 1);
    }

    @Test
    void aSettlementBeforeTheTimerCancelsItAndNothingIsAsked() {
        String txId = txId();
        UUID paymentId = accepted(txId);

        publishCsmEvent(new CreditTransferSettled(new TxId(txId), PEER, Direction.INCOMING, null, CLOCK.instant(),
                SettlementSource.STATUS_REPORT), txId, UUID.randomUUID());
        await().atMost(AWAIT).until(() -> "SETTLED".equals(probe.row(paymentId).state()));
        assertThat(probe.timers(paymentId)).containsExactly("SETTLEMENT_OVERDUE|CANCELLED");

        CLOCK.advance(PAST_OVERDUE);

        await().during(QUIET).atMost(AWAIT).until(() -> statusRequests(txId).isEmpty());
        assertThat(probe.row(paymentId)).isEqualTo(new Row("SETTLED", null, null, true));
    }

    @Test
    void confirmingAnUnresolvedIncomingInvestigationRejectedCancelsTheCreditAndRecordsTheOperator() {
        String txId = txId();
        UUID paymentId = unresolved(txId);
        UUID caseId = openCase(paymentId);

        var response = resolve(caseId, Map.of("resolution", "CONFIRMED_REJECTED", "reasonCode", "AB05",
                "comment", "CSM confirmed by phone"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId))
                .isEqualTo(new Row("REJECTED", "AB05", null, true)));
        assertThat(probe.cancelCreditCalls(paymentId)).hasSize(1);
        assertThat(probe.confirmCreditCalls(paymentId)).isEmpty();
        assertThat(probe.finalEvents(paymentId)).containsExactly("PaymentRejected|AB05");
        assertThat(transitionsWithActor(paymentId))
                .contains("INVESTIGATING|REJECTED|OPERATOR_CONFIRMED_REJECTED|" + OPERATOR);
        assertThat(probe.cases(paymentId)).containsExactly("UNRESOLVED_INVESTIGATION|RESOLVED");
    }

    @Test
    void confirmingAnUnresolvedIncomingInvestigationSettledConfirmsTheCredit() {
        String txId = txId();
        UUID paymentId = unresolved(txId);
        UUID caseId = openCase(paymentId);

        assertThat(resolve(caseId, Map.of("resolution", "CONFIRMED_SETTLED")).getStatusCode().value()).isEqualTo(200);

        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId))
                .isEqualTo(new Row("SETTLED", null, null, true)));
        assertThat(probe.confirmCreditCalls(paymentId)).hasSize(1);
        assertThat(probe.finalEvents(paymentId)).containsExactly("PaymentSettled|");
        assertThat(transitionsWithActor(paymentId))
                .contains("INVESTIGATING|INVESTIGATING|OPERATOR_CONFIRMED_SETTLED|" + OPERATOR);
    }

    @Test
    void retryingAnUnresolvedIncomingInvestigationAsksAgainWithTheNextAttempt() {
        String txId = txId();
        UUID paymentId = unresolved(txId);
        UUID caseId = openCase(paymentId);
        int asked = statusRequests(txId).size();

        assertThat(resolve(caseId, Map.of("resolution", "RETRY_INVESTIGATION")).getStatusCode().value())
                .isEqualTo(200);

        await().atMost(AWAIT).until(() -> statusRequests(txId).size() == asked + 1);
        assertThat(statusRequests(txId).getLast().get("attempt").asInt()).isEqualTo(asked + 1);
        assertThat(statusRequests(txId).getLast().get("direction").asString()).isEqualTo("INCOMING");
        assertThat(probe.row(paymentId)).isEqualTo(new Row("INVESTIGATING", null, null, false));
    }

    /** Received now and answered accepting: {@code ACCEPTED}. */
    private UUID accepted(String txId) {
        publishCsmEvent(creditTransferReceived(PEER, txId, Ibans.own()), txId, UUID.randomUUID());
        UUID paymentId = await().atMost(AWAIT).until(() -> probe.incoming(PEER.value(), txId), Optional::isPresent)
                .orElseThrow();
        await().atMost(AWAIT).until(() -> "ACCEPT_REQUESTED".equals(probe.row(paymentId).state()));
        publishCsmEvent(new AnswerSent(new TxId(txId), PEER, Direction.INCOMING, AnswerStatus.ACCP, null,
                DecidedBy.VERDICT, CLOCK.instant()), txId, UUID.randomUUID());
        await().atMost(AWAIT).until(() -> "ACCEPTED".equals(probe.row(paymentId).state()));
        return paymentId;
    }

    /** Accepted and overdue: {@code INVESTIGATING}, its first status request out. */
    private UUID investigating(String txId) {
        UUID paymentId = accepted(txId);
        CLOCK.advance(PAST_OVERDUE);
        await().atMost(AWAIT).until(() -> statusRequests(txId).size() == 1);
        return paymentId;
    }

    /** Investigating past the budget: the retry due then opens the {@code UNRESOLVED_INVESTIGATION} case. */
    private UUID unresolved(String txId) {
        UUID paymentId = investigating(txId);
        CLOCK.advance(BUDGET.plusMinutes(5));
        return paymentId;
    }

    private UUID openCase(UUID paymentId) {
        await().atMost(AWAIT).until(() -> probe.cases(paymentId).contains("UNRESOLVED_INVESTIGATION|OPEN"));
        return jdbc.sql("""
                        SELECT case_id FROM exception_cases
                        WHERE payment_id = ? AND type = 'UNRESOLVED_INVESTIGATION' AND status = 'OPEN'""")
                .param(paymentId).query(UUID.class).single();
    }

    private ResponseEntity<String> resolve(UUID caseId, Map<String, String> body) {
        String token = StaffTokens.token(OPERATOR, "BACKOFFICE_OPERATOR");
        return http().post().uri("/v1/exception-cases/" + caseId + "/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(token))
                .body(JSON.writeValueAsString(new LinkedHashMap<>(body)))
                .retrieve().toEntity(String.class);
    }

    /** {@code from|to|cause|actor}. */
    private List<String> transitionsWithActor(UUID paymentId) {
        return jdbc.sql("""
                        SELECT coalesce(from_state, '-') || '|' || to_state || '|' || cause || '|' || actor
                        FROM payment_transitions WHERE payment_id = ? ORDER BY id""")
                .param(paymentId).query(String.class).list();
    }

    private List<JsonNode> statusRequests(String txId) {
        return probe.csmCommands(txId).stream()
                .filter(command -> command.startsWith("RequestPaymentStatus|"))
                .map(command -> JSON.readTree(command.substring(command.indexOf('|') + 1)))
                .toList();
    }

    private Instant dueAt(UUID paymentId, String type) {
        return jdbc.sql("""
                        SELECT due_at FROM payment_timers
                        WHERE payment_id = ? AND type = ? AND status = 'SCHEDULED'""")
                .param(paymentId).param(type).query(Timestamp.class).single().toInstant();
    }
}
