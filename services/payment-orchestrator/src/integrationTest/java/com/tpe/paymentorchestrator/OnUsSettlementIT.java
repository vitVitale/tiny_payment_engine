package com.tpe.paymentorchestrator;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The on-us happy path against risk-service and ledger-service in WireMock (specs/11-payment-orchestrator.md §6.2,
 * UC-04 3, 4a): hold, payee risk, settle on-us.
 */
class OnUsSettlementIT extends OrchestratorStack {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration AWAIT = Duration.ofSeconds(10);

    /** {@code journalEntryId} and {@code creditorAccountId} of {@code settle-on-us-settled.json}. */
    private static final String ENTRY = "0192f0e4-7b1c-7cc3-a1b2-0000000000c1";
    private static final String PAYEE_ACCOUNT = "0192f0e4-7b1c-7cc3-a1b2-0000000000b2";
    /** {@code accountId} of {@code create-hold-created.json}. */
    private static final String PAYER_ACCOUNT = "0192f0e4-7b1c-7cc3-a1b2-0000000000b1";

    @Autowired
    JdbcClient jdbc;

    @Test
    void ORC_AC_11_anOnUsPaymentIsHeldItsPayeeAssessedAndSettledOnUsOnce() {
        riskAnswers("allow");
        ledgerAnswers("create-hold-created", "settle-on-us-settled");
        String payer = Ibans.own();
        String payee = Ibans.own();

        UUID paymentId = submit(payer, payee);

        await().atMost(AWAIT).untilAsserted(() -> assertThat(row(paymentId)).isEqualTo(
                new Row("SETTLED", null, null, true, ENTRY)));
        // the payer's risk, then the payee's: same payment, direction INCOMING
        assertThat(riskCalls(paymentId)).extracting(call -> call.get("assessmentId").asString())
                .containsExactly(paymentId + ":OUTGOING", paymentId + ":INCOMING");
        List<LoggedRequest> holds = holdCalls(paymentId);
        assertThat(holds).hasSize(1);
        assertThat(holds.getFirst().getHeader("Idempotency-Key")).isEqualTo("hold-create:" + paymentId);
        assertThat(JSON.readTree(holds.getFirst().getBodyAsString()).get("debtorIban").asString()).isEqualTo(payer);
        List<LoggedRequest> settlements = LEDGER.findAll(
                postRequestedFor(urlEqualTo("/v1/holds/" + paymentId + "/settle-on-us")));
        assertThat(settlements).hasSize(1);
        assertThat(settlements.getFirst().getHeader("Idempotency-Key")).isEqualTo("hold-settle-on-us:" + paymentId);
        assertThat(JSON.readTree(settlements.getFirst().getBodyAsString()).get("creditorIban").asString())
                .isEqualTo(payee);
        // the only message: no command for the CSM
        assertThat(outbox(paymentId)).containsExactly("payments.events|PaymentSettled");
        JsonNode settled = JSON.readTree(finalEvent(paymentId));
        assertThat(settled.get("onUs").asBoolean()).isTrue();
        assertThat(settled.get("bookingEntryId").asString()).isEqualTo(ENTRY);
        assertThat(settled.get("debtor").get("accountId").asString()).isEqualTo(PAYER_ACCOUNT);
        assertThat(settled.get("creditor").get("accountId").asString()).isEqualTo(PAYEE_ACCOUNT);
    }

    @Test
    void theOnUsPathRecordsEachStateItPasses() {
        riskAnswers("allow");
        ledgerAnswers("create-hold-created", "settle-on-us-settled");

        UUID paymentId = submit(Ibans.own(), Ibans.own());

        await().atMost(AWAIT).until(() -> "SETTLED".equals(row(paymentId).state()));
        assertThat(transitions(paymentId)).containsExactly("-|RECEIVED|SUBMITTED", "RECEIVED|RISK_CHECKED|RISK_ALLOW",
                "RISK_CHECKED|FUNDS_RESERVED|HOLD_CREATED", "FUNDS_RESERVED|SETTLED|SETTLED_ON_US");
    }

    @Test
    void aCreateHoldRetriedAfterALostAnswerReusesItsIdempotencyKey() {
        riskAnswers("allow");
        ledgerAnswers("create-hold-created", "settle-on-us-settled");
        // the first answer is lost on the way back; the hold may well exist
        LEDGER.stubFor(post(urlEqualTo("/v1/holds")).inScenario("lost answer").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)).willSetStateTo("answered"));

        UUID paymentId = submit(Ibans.own(), Ibans.own());

        await().atMost(AWAIT).until(() -> holdCalls(paymentId).size() == 1 && !leased(paymentId));
        // the retry is due 100 ms later; the sweeper hands it to the runner
        CLOCK.advance(Duration.ofMillis(150));
        await().atMost(AWAIT).until(() -> "SETTLED".equals(row(paymentId).state()));
        List<LoggedRequest> holds = holdCalls(paymentId);
        assertThat(holds).hasSize(2);
        assertThat(holds).extracting(call -> call.getHeader("Idempotency-Key"))
                .containsOnly("hold-create:" + paymentId);
        assertThat(holds).extracting(call -> JSON.readTree(call.getBodyAsString()).get("holdId").asString())
                .containsOnly(paymentId.toString());
        assertThat(LEDGER.findAll(postRequestedFor(urlPathMatching("/v1/holds/" + paymentId + "/settle-on-us"))))
                .hasSize(1);
    }

    private record Row(String state, String reasonCode, String pendingAction, boolean finalEventEmitted,
            String bookingEntryId) {}

    private Row row(UUID paymentId) {
        return jdbc.sql("""
                        SELECT state, reason_code, pending_action, final_event_emitted, booking_entry_id::text
                        FROM payments WHERE payment_id = ?""")
                .param(paymentId)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                        rs.getString(5)))
                .single();
    }

    private boolean leased(UUID paymentId) {
        return jdbc.sql("""
                        SELECT next_action_at > CAST(? AS TIMESTAMPTZ) + interval '1 second'
                        FROM payments WHERE payment_id = ?""")
                .param(Timestamp.from(CLOCK.instant())).param(paymentId).query(Boolean.class).single();
    }

    private List<String> transitions(UUID paymentId) {
        return jdbc.sql("""
                        SELECT coalesce(from_state, '-') || '|' || to_state || '|' || cause
                        FROM payment_transitions WHERE payment_id = ? ORDER BY id""")
                .param(paymentId).query(String.class).list();
    }

    /** Every outbox row of the payment, whatever its topic. */
    private List<String> outbox(UUID paymentId) {
        return jdbc.sql("""
                        SELECT topic || '|' || message_type FROM outbox
                        WHERE message_key = ? OR payload ->> 'paymentId' = ? ORDER BY id""")
                .param(paymentId.toString()).param(paymentId.toString()).query(String.class).list();
    }

    private String finalEvent(UUID paymentId) {
        return jdbc.sql("""
                        SELECT payload::text FROM outbox
                        WHERE topic = 'payments.events' AND message_key = ? ORDER BY id""")
                .param(paymentId.toString()).query(String.class).single();
    }

    private List<JsonNode> riskCalls(UUID paymentId) {
        return RISK.findAll(postRequestedFor(urlEqualTo("/v1/risk/assessments"))
                        .withRequestBody(matchingJsonPath("$.paymentId", equalTo(paymentId.toString()))))
                .stream().map(call -> JSON.readTree(call.getBodyAsString())).toList();
    }

    private List<LoggedRequest> holdCalls(UUID paymentId) {
        return LEDGER.findAll(postRequestedFor(urlEqualTo("/v1/holds"))
                .withRequestBody(matchingJsonPath("$.holdId", equalTo(paymentId.toString()))));
    }

    private UUID submit(String debtorIban, String creditorIban) {
        Map<String, Object> request = PaymentCommandIT.request(debtorIban, creditorIban);
        String body = http().post().uri("/v1/payments").contentType(MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsString(request)).retrieve().body(String.class);
        return UUID.fromString(JSON.readTree(body).get("paymentId").asString());
    }
}
