package com.tpe.paymentorchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.contracts.CreditTransferRejected;
import com.tpe.contracts.CreditTransferSettled;
import com.tpe.contracts.Direction;
import com.tpe.contracts.SettlementSource;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentorchestrator.SagaProbe.Row;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * The exception case API for {@code BACKOFFICE_OPERATOR} (specs/11-payment-orchestrator.md §3.2): list, inspect and
 * resolve cases; a resolution drives the payment on and is recorded with the operator's {@code sub}.
 */
class ExceptionCaseApiIT extends OrchestratorStack {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration AWAIT = Duration.ofSeconds(15);
    /** Long enough for the listener, the runner, the sweeper and the timers to have acted many times. */
    private static final Duration QUIET = Duration.ofSeconds(1);
    private static final Duration PAST_DEADLINE = Duration.ofSeconds(21);
    private static final Duration BUDGET = Duration.ofHours(1);
    private static final String OPERATOR = "ops-anna";

    @Autowired
    JdbcClient jdbc;

    private SagaProbe probe;
    private String operator;

    @BeforeEach
    void probe() {
        probe = new SagaProbe(jdbc, CLOCK, RISK, LEDGER);
        riskAnswers("allow");
        ledgerAnswers("create-hold-created", "settle-hold-settled", "release-released");
        operator = StaffTokens.token(OPERATOR, "BACKOFFICE_OPERATOR");
    }

    @Test
    void ORC_AC_20_confirmingAnUnresolvedInvestigationRejectedReleasesTheHoldAndRecordsTheOperator() {
        UUID paymentId = unresolved(1);
        UUID caseId = openCase(paymentId, "UNRESOLVED_INVESTIGATION");

        var response = resolve(caseId, Map.of("resolution", "CONFIRMED_REJECTED", "reasonCode", "AB05",
                "comment", "CSM confirmed by phone"), operator);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode resolved = JSON.readTree(response.getBody());
        assertThat(resolved.get("status").asString()).isEqualTo("RESOLVED");
        assertThat(resolved.get("resolution").asString()).isEqualTo("CONFIRMED_REJECTED");
        assertThat(resolved.get("resolvedBy").asString()).isEqualTo(OPERATOR);
        assertThat(resolved.get("comment").asString()).isEqualTo("CSM confirmed by phone");
        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId)).isEqualTo(
                new Row("REJECTED", "AB05", null, true)));
        assertThat(probe.releaseCalls(paymentId)).hasSize(1);
        assertThat(probe.settleCalls(paymentId)).isEmpty();
        assertThat(probe.finalEvents(paymentId)).containsExactly("PaymentRejected|AB05");
        assertThat(transitionsWithActor(paymentId))
                .contains("INVESTIGATING|REJECTED|OPERATOR_CONFIRMED_REJECTED|" + OPERATOR);
        assertThat(probe.cases(paymentId)).containsExactly("UNRESOLVED_INVESTIGATION|RESOLVED");
    }

    @Test
    void ORC_AC_27_retryingAnInvestigationContinuesTheAttemptsAndRestartsBackoffAndBudget() {
        UUID paymentId = unresolved(2);
        String txId = probe.txId(paymentId);
        UUID caseId = openCase(paymentId, "UNRESOLVED_INVESTIGATION");
        Instant resolvedAt = CLOCK.instant();

        assertThat(resolve(caseId, Map.of("resolution", "RETRY_INVESTIGATION"), operator).getStatusCode().value())
                .isEqualTo(200);

        await().atMost(AWAIT).until(() -> statusRequests(txId).size() == 3);
        assertThat(statusRequests(txId)).extracting(request -> request.get("attempt").asInt())
                .containsExactly(1, 2, 3);
        assertThat(jdbc.sql("SELECT investigation_started_at FROM payments WHERE payment_id = ?").param(paymentId)
                .query(Timestamp.class).single().toInstant()).isEqualTo(resolvedAt);
        assertThat(scheduledRetry(paymentId)).as("the back-off from its first value")
                .isEqualTo(resolvedAt.plusSeconds(5));
        assertThat(transitionsWithActor(paymentId))
                .contains("INVESTIGATING|INVESTIGATING|OPERATOR_RETRY_INVESTIGATION|" + OPERATOR);

        CLOCK.advance(Duration.ofSeconds(5));
        await().atMost(AWAIT).until(() -> statusRequests(txId).size() == 4);
        assertThat(scheduledRetry(paymentId)).isEqualTo(resolvedAt.plusSeconds(5 + 15));

        // a full budget after the resolution, not after the first investigation: still asking
        CLOCK.advance(Duration.between(CLOCK.instant(), resolvedAt.plus(BUDGET)));
        await().atMost(AWAIT).until(() -> statusRequests(txId).size() == 5);
        assertThat(probe.cases(paymentId)).containsExactly("UNRESOLVED_INVESTIGATION|RESOLVED");

        CLOCK.advance(Duration.ofSeconds(60));
        await().atMost(AWAIT).until(() -> probe.cases(paymentId).size() == 2);
        assertThat(probe.cases(paymentId)).containsExactly("UNRESOLVED_INVESTIGATION|RESOLVED",
                "UNRESOLVED_INVESTIGATION|OPEN");
        assertThat(statusRequests(txId)).extracting(request -> request.get("attempt").asInt())
                .containsExactly(1, 2, 3, 4, 5);
        assertThat(probe.releaseCalls(paymentId)).isEmpty();
    }

    @Test
    void confirmingAnUnresolvedInvestigationSettledSettlesTheHoldOnce() {
        UUID paymentId = unresolved(1);
        UUID caseId = openCase(paymentId, "UNRESOLVED_INVESTIGATION");

        assertThat(resolve(caseId, Map.of("resolution", "CONFIRMED_SETTLED"), operator).getStatusCode().value())
                .isEqualTo(200);

        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId)).isEqualTo(
                new Row("SETTLED", null, null, true)));
        // the CSM's own answer after all: the outcome is recorded already
        publishCsmEvent(new CreditTransferSettled(new TxId(probe.txId(paymentId)), debtorAgent(paymentId),
                Direction.OUTGOING, new PaymentId(paymentId), CLOCK.instant(), SettlementSource.INVESTIGATION),
                probe.txId(paymentId), UUID.randomUUID());
        await().during(QUIET).atMost(AWAIT).until(() -> probe.settleCalls(paymentId).size() == 1);
        assertThat(probe.finalEvents(paymentId)).containsExactly("PaymentSettled|");
        assertThat(transitionsWithActor(paymentId))
                .contains("INVESTIGATING|INVESTIGATING|OPERATOR_CONFIRMED_SETTLED|" + OPERATOR);
    }

    @Test
    void aContradictionIsAcknowledgedWithoutChangingThePayment() {
        UUID paymentId = sent();
        String txId = probe.txId(paymentId);
        publishCsmEvent(new CreditTransferRejected(new TxId(txId), debtorAgent(paymentId), Direction.OUTGOING,
                new PaymentId(paymentId), ReasonCode.AC04, CLOCK.instant(), SettlementSource.STATUS_REPORT), txId,
                UUID.randomUUID());
        await().atMost(AWAIT).until(() -> probe.row(paymentId).finalEventEmitted());
        publishCsmEvent(new CreditTransferSettled(new TxId(txId), debtorAgent(paymentId), Direction.OUTGOING,
                new PaymentId(paymentId), CLOCK.instant(), SettlementSource.STATUS_REPORT), txId, UUID.randomUUID());
        UUID caseId = openCase(paymentId, "CONTRADICTING_CSM_OUTCOME");

        var refused = resolve(caseId, Map.of("resolution", "RETRY_NOW"), operator);
        assertThat(refused.getStatusCode().value()).isEqualTo(422);
        assertThat(refused.getBody()).contains("\"code\":\"RESOLUTION_NOT_ALLOWED\"");

        assertThat(resolve(caseId, Map.of("resolution", "ACKNOWLEDGED"), operator).getStatusCode().value())
                .isEqualTo(200);
        assertThat(probe.row(paymentId)).isEqualTo(new Row("REJECTED", "AC04", null, true));
        assertThat(transitionsWithActor(paymentId)).contains("REJECTED|REJECTED|OPERATOR_ACKNOWLEDGED|" + OPERATOR);

        var again = resolve(caseId, Map.of("resolution", "ACKNOWLEDGED"), operator);
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody()).contains("\"code\":\"CASE_ALREADY_RESOLVED\"");
    }

    @Test
    void aStuckLedgerActionIsRetriedNow() {
        ledgerAnswers("create-hold-created", "settle-on-us-unavailable");
        UUID paymentId = submit(Ibans.own());
        await().atMost(AWAIT).until(() -> !probe.settleOnUsCalls(paymentId).isEmpty());
        CLOCK.advance(Duration.ofMinutes(11));
        UUID caseId = openCase(paymentId, "LEDGER_ACTION_STUCK");
        ledgerAnswers("create-hold-created", "settle-on-us-settled");

        assertThat(resolve(caseId, Map.of("resolution", "RETRY_NOW"), operator).getStatusCode().value())
                .isEqualTo(200);

        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId)).isEqualTo(
                new Row("SETTLED", null, null, true)));
        assertThat(transitionsWithActor(paymentId))
                .contains("FUNDS_RESERVED|FUNDS_RESERVED|OPERATOR_RETRY_NOW|" + OPERATOR);
    }

    @Test
    void aCaseShowsItsPaymentTransitionsAndCsmEvents() {
        UUID paymentId = sent();
        String txId = probe.txId(paymentId);
        publishCsmEvent(new CreditTransferRejected(new TxId(txId), debtorAgent(paymentId), Direction.OUTGOING,
                new PaymentId(paymentId), ReasonCode.AC04, CLOCK.instant(), SettlementSource.STATUS_REPORT), txId,
                UUID.randomUUID());
        await().atMost(AWAIT).until(() -> probe.row(paymentId).finalEventEmitted());
        publishCsmEvent(new CreditTransferSettled(new TxId(txId), debtorAgent(paymentId), Direction.OUTGOING,
                new PaymentId(paymentId), CLOCK.instant(), SettlementSource.STATUS_REPORT), txId, UUID.randomUUID());
        UUID caseId = openCase(paymentId, "CONTRADICTING_CSM_OUTCOME");

        var response = get("/v1/exception-cases/" + caseId, operator);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.getBody());
        assertThat(body.get("caseId").asString()).isEqualTo(caseId.toString());
        assertThat(body.get("type").asString()).isEqualTo("CONTRADICTING_CSM_OUTCOME");
        assertThat(body.get("status").asString()).isEqualTo("OPEN");
        assertThat(body.get("details").get("event").asString()).isEqualTo("CreditTransferSettled");
        assertThat(body.get("payment").get("paymentId").asString()).isEqualTo(paymentId.toString());
        assertThat(body.get("payment").get("state").asString()).isEqualTo("REJECTED");
        assertThat(body.get("transitions")).extracting(t -> t.get("cause").asString())
                .startsWith("SUBMITTED").contains("CSM_REJECTED");
        assertThat(body.get("csmEvents")).extracting(e -> e.get("type").asString() + "|" + e.get("outcome").asString())
                .containsExactly("CreditTransferRejected|REJECTED-AC04", "CreditTransferSettled|SETTLED");
        assertThat(get("/v1/exception-cases/" + UUID.randomUUID(), operator).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void casesAreListedByStatusAndTypeAPageAtATime() {
        UUID first = openCase(unresolved(1), "UNRESOLVED_INVESTIGATION");
        UUID second = openCase(unresolved(1), "UNRESOLVED_INVESTIGATION");

        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            var page = JSON.readTree(get("/v1/exception-cases?status=OPEN&type=UNRESOLVED_INVESTIGATION&limit=1"
                    + (cursor == null ? "" : "&cursor=" + cursor), operator).getBody());
            assertThat(page.get("cases").size()).isLessThanOrEqualTo(1);
            page.get("cases").forEach(c -> {
                assertThat(c.get("status").asString()).isEqualTo("OPEN");
                assertThat(c.get("type").asString()).isEqualTo("UNRESOLVED_INVESTIGATION");
                seen.add(c.get("caseId").asString());
            });
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
        } while (cursor != null);

        assertThat(seen).doesNotHaveDuplicates().containsSubsequence(first.toString(), second.toString());
        assertThat(get("/v1/exception-cases?cursor=not-ours", operator).getBody())
                .contains("\"code\":\"VALIDATION_FAILED\"", "\"field\":\"cursor\"");
    }

    @Test
    void aRejectionNeedsAReasonCode() {
        UUID caseId = openCase(unresolved(1), "UNRESOLVED_INVESTIGATION");

        var response = resolve(caseId, Map.of("resolution", "CONFIRMED_REJECTED"), operator);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("\"field\":\"reasonCode\"");
    }

    @Test
    void anInvestigationTheCsmAnsweredAfterAllIsClosedOnlyByAnAgreeingConfirmation() {
        UUID paymentId = unresolved(1);
        UUID caseId = openCase(paymentId, "UNRESOLVED_INVESTIGATION");
        publishCsmEvent(new CreditTransferSettled(new TxId(probe.txId(paymentId)), debtorAgent(paymentId),
                Direction.OUTGOING, new PaymentId(paymentId), CLOCK.instant(), SettlementSource.INVESTIGATION),
                probe.txId(paymentId), UUID.randomUUID());
        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId)).isEqualTo(
                new Row("SETTLED", null, null, true)));

        for (Map<String, String> outdated : List.of(Map.of("resolution", "RETRY_INVESTIGATION"),
                Map.of("resolution", "CONFIRMED_REJECTED", "reasonCode", "AB05"))) {
            var refused = resolve(caseId, outdated, operator);
            assertThat(refused.getStatusCode().value()).isEqualTo(409);
            assertThat(refused.getBody()).contains("\"code\":\"CASE_OUTDATED\"");
        }
        assertThat(resolve(caseId, Map.of("resolution", "CONFIRMED_SETTLED"), operator).getStatusCode().value())
                .isEqualTo(200);

        assertThat(probe.cases(paymentId)).containsExactly("UNRESOLVED_INVESTIGATION|RESOLVED");
        assertThat(probe.row(paymentId)).isEqualTo(new Row("SETTLED", null, null, true));
        assertThat(probe.settleCalls(paymentId)).hasSize(1);
        assertThat(probe.finalEvents(paymentId)).containsExactly("PaymentSettled|");
        assertThat(transitionsWithActor(paymentId)).endsWith("SETTLED|SETTLED|OPERATOR_CONFIRMED_SETTLED|" + OPERATOR);
    }

    /** A payment to the peer bank whose investigation ran out after {@code requests} status requests. */
    private UUID unresolved(int requests) {
        UUID paymentId = sent();
        String txId = probe.txId(paymentId);
        CLOCK.advance(PAST_DEADLINE);
        Instant started = CLOCK.instant();
        await().atMost(AWAIT).until(() -> statusRequests(txId).size() == 1);
        if (requests == 2) {
            CLOCK.advance(Duration.ofSeconds(5));
            await().atMost(AWAIT).until(() -> statusRequests(txId).size() == 2);
        }
        CLOCK.advance(Duration.between(CLOCK.instant(), started.plus(BUDGET)).plusSeconds(60 * 5));
        return paymentId;
    }

    private UUID openCase(UUID paymentId, String type) {
        await().atMost(AWAIT).until(() -> probe.cases(paymentId).contains(type + "|OPEN"));
        return jdbc.sql("SELECT case_id FROM exception_cases WHERE payment_id = ? AND type = ? AND status = 'OPEN'")
                .param(paymentId).param(type).query(UUID.class).single();
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

    private Instant scheduledRetry(UUID paymentId) {
        return jdbc.sql("""
                        SELECT due_at FROM payment_timers
                        WHERE payment_id = ? AND type = 'INVESTIGATION_RETRY' AND status = 'SCHEDULED'""")
                .param(paymentId).query(Timestamp.class).single().toInstant();
    }

    private UUID sent() {
        UUID paymentId = submit(Ibans.peer());
        await().atMost(AWAIT).until(() -> "SENT_TO_CSM".equals(probe.row(paymentId).state()));
        return paymentId;
    }

    private UUID submit(String creditorIban) {
        Map<String, Object> request = PaymentCommandIT.request(Ibans.own(), creditorIban);
        String body = http().post().uri("/v1/payments").contentType(MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsString(request)).retrieve().body(String.class);
        return UUID.fromString(JSON.readTree(body).get("paymentId").asString());
    }

    private Bic debtorAgent(UUID paymentId) {
        return new Bic(jdbc.sql("SELECT debtor_agent_bic FROM payments WHERE payment_id = ?").param(paymentId)
                .query(String.class).single());
    }

    private ResponseEntity<String> get(String path, String token) {
        return http().get().uri(path)
                .headers(headers -> {
                    if (token != null) {
                        headers.setBearerAuth(token);
                    }
                })
                .retrieve().toEntity(String.class);
    }

    private ResponseEntity<String> resolve(UUID caseId, Map<String, String> body, String token) {
        return http().post().uri("/v1/exception-cases/" + caseId + "/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    if (token != null) {
                        headers.setBearerAuth(token);
                    }
                })
                .body(JSON.writeValueAsString(new LinkedHashMap<>(body)))
                .retrieve().toEntity(String.class);
    }
}
