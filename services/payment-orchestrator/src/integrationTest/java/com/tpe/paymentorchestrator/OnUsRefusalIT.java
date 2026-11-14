package com.tpe.paymentorchestrator;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every way an on-us payment is refused ends in one {@code PaymentRejected} with its reason and no hold left behind
 * (specs/11-payment-orchestrator.md §6.2, UC-04 3a, 3b, 4a-1, ADR-009); against risk-service and ledger-service in
 * WireMock.
 */
class OnUsRefusalIT extends OrchestratorStack {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration AWAIT = Duration.ofSeconds(10);

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MeterRegistry meters;

    @Test
    void ORC_AC_05_insufficientFundsRejectsAm04WithoutARelease() {
        riskAnswers("allow");
        ledgerAnswers("create-hold-insufficient-funds", "release-released");
        double before = rejected("AM04", "BUSINESS");

        UUID paymentId = submit();

        await().atMost(AWAIT).untilAsserted(() -> assertThat(row(paymentId)).isEqualTo(
                new Row("REJECTED", "AM04", null, true)));
        assertThat(transitions(paymentId)).containsExactly("-|RECEIVED|SUBMITTED", "RECEIVED|RISK_CHECKED|RISK_ALLOW",
                "RISK_CHECKED|REJECTED|HOLD_REFUSED");
        assertThat(finalEvents(paymentId)).containsExactly("PaymentRejected|AM04");
        // no hold, so nothing to release
        assertThat(releaseCalls(paymentId)).isEmpty();
        assertThat(rejected("AM04", "BUSINESS")).isEqualTo(before + 1);
    }

    @Test
    void ORC_AC_12_aBlockedPayeeRejectsAc06AndReleasesTheHold() {
        riskAnswers("allow");
        ledgerAnswers("create-hold-created", "settle-on-us-blocked-payee", "release-released");
        double before = rejected("AC06", "BUSINESS");

        UUID paymentId = submit();

        await().atMost(AWAIT).untilAsserted(() -> assertThat(row(paymentId)).isEqualTo(
                new Row("REJECTED", "AC06", null, true)));
        assertThat(transitions(paymentId)).containsExactly("-|RECEIVED|SUBMITTED", "RECEIVED|RISK_CHECKED|RISK_ALLOW",
                "RISK_CHECKED|FUNDS_RESERVED|HOLD_CREATED", "FUNDS_RESERVED|REJECTED|SETTLE_ON_US_REFUSED");
        List<LoggedRequest> releases = releaseCalls(paymentId);
        assertThat(releases).hasSize(1);
        assertThat(releases.getFirst().getHeader("Idempotency-Key")).isEqualTo("hold-release:" + paymentId);
        assertThat(finalEvents(paymentId)).containsExactly("PaymentRejected|AC06");
        // counted once, when rejected, not again when released
        assertThat(rejected("AC06", "BUSINESS")).isEqualTo(before + 1);
    }

    @ParameterizedTest
    @CsvSource({"settle-on-us-unknown-payee, AC01", "settle-on-us-closed-payee, AC04"})
    void anUnknownOrClosedPayeeRejectsWithItsReasonAndReleasesTheHold(String settlement, String reason) {
        riskAnswers("allow");
        ledgerAnswers("create-hold-created", settlement, "release-released");

        UUID paymentId = submit();

        await().atMost(AWAIT).untilAsserted(() -> assertThat(row(paymentId)).isEqualTo(
                new Row("REJECTED", reason, null, true)));
        assertThat(releaseCalls(paymentId)).hasSize(1);
        assertThat(finalEvents(paymentId)).containsExactly("PaymentRejected|" + reason);
    }

    @ParameterizedTest
    @CsvSource({"deny-fr01, PAYEE_RISK_DENY", "review, PAYEE_RISK_REVIEW"})
    void aDeniedOrReviewedPayeeRejectsFr01AndReleasesTheHoldWithoutSettling(String payeeRisk, String cause) {
        riskAnswers("allow", payeeRisk);
        ledgerAnswers("create-hold-created", "settle-on-us-settled", "release-released");

        UUID paymentId = submit();

        await().atMost(AWAIT).untilAsserted(() -> assertThat(row(paymentId)).isEqualTo(
                new Row("REJECTED", "FR01", null, true)));
        assertThat(transitions(paymentId)).endsWith("FUNDS_RESERVED|REJECTED|" + cause);
        assertThat(releaseCalls(paymentId)).hasSize(1);
        assertThat(LEDGER.findAll(postRequestedFor(urlEqualTo("/v1/holds/" + paymentId + "/settle-on-us"))))
                .isEmpty();
        assertThat(finalEvents(paymentId)).containsExactly("PaymentRejected|FR01");
    }

    @Test
    void ORC_AC_22_aHoldFailingTechnicallyUntilTheGiveUpMarginRejectsMs03AndIsReleased() {
        riskAnswers("allow");
        ledgerAnswers("create-hold-unavailable", "release-released");
        double before = rejected("MS03", "TECHNICAL");
        long transitionsBefore = lastTransitionId();

        UUID paymentId = submit();

        await().atMost(AWAIT).until(() -> holdCalls(paymentId) >= 1 && !leased(paymentId));
        // send_by is 10 s after acceptance, the give-up margin 2 s
        CLOCK.advance(Duration.ofSeconds(8));
        await().atMost(AWAIT).untilAsserted(() -> assertThat(row(paymentId)).isEqualTo(
                new Row("REJECTED", "MS03", null, true)));
        assertThat(transitions(paymentId)).endsWith("RISK_CHECKED|REJECTED|HOLD_UNAVAILABLE");
        // the hold may exist: the release frees it, or tombstones it if it never came (ADR-009)
        assertThat(releaseCalls(paymentId)).hasSize(1);
        assertThat(finalEvents(paymentId)).containsExactly("PaymentRejected|MS03");
        // moving the clock lets payments other tests left waiting for their hold give up as well: each counts once
        await().atMost(AWAIT).untilAsserted(() -> assertThat(rejected("MS03", "TECHNICAL"))
                .isEqualTo(before + rejectedMs03Since(transitionsBefore)));
    }

    @Test
    void aSettlementFailingTechnicallyPastStuckAfterOpensOneLedgerActionStuckCase() {
        riskAnswers("allow");
        ledgerAnswers("create-hold-created", "settle-on-us-unavailable");

        UUID paymentId = submit();

        await().atMost(AWAIT).until(() -> settleCalls(paymentId) >= 1 && !leased(paymentId));
        assertThat(cases(paymentId)).isEmpty();
        CLOCK.advance(Duration.ofMinutes(10));
        await().atMost(AWAIT).until(() -> !cases(paymentId).isEmpty());
        // still retried, but the case stays the one
        int calls = settleCalls(paymentId);
        CLOCK.advance(Duration.ofMinutes(1));
        await().atMost(AWAIT).until(() -> settleCalls(paymentId) > calls);
        assertThat(cases(paymentId)).containsExactly("LEDGER_ACTION_STUCK|OPEN|SETTLE_ON_US");
        assertThat(row(paymentId)).isEqualTo(new Row("FUNDS_RESERVED", null, "SETTLE_ON_US", false));
    }

    @Test
    void aReleaseAnsweredHoldSettledOpensAContradictionCaseAndNoFinalEvent() {
        riskAnswers("allow");
        ledgerAnswers("create-hold-created", "settle-on-us-blocked-payee", "release-hold-settled");

        UUID paymentId = submit();

        await().atMost(AWAIT).until(() -> !cases(paymentId).isEmpty());
        assertThat(cases(paymentId)).containsExactly("CONTRADICTING_CSM_OUTCOME|OPEN|RELEASE_HOLD");
        assertThat(row(paymentId)).isEqualTo(new Row("REJECTED", "AC06", null, false));
        assertThat(releaseCalls(paymentId)).hasSize(1);
        assertThat(finalEvents(paymentId)).isEmpty();
    }

    private record Row(String state, String reasonCode, String pendingAction, boolean finalEventEmitted) {}

    private Row row(UUID paymentId) {
        return jdbc.sql("SELECT state, reason_code, pending_action, final_event_emitted FROM payments "
                        + "WHERE payment_id = ?")
                .param(paymentId)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)))
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

    private List<String> finalEvents(UUID paymentId) {
        return jdbc.sql("""
                        SELECT message_type || '|' || (payload ->> 'reasonCode') FROM outbox
                        WHERE topic = 'payments.events' AND message_key = ? ORDER BY id""")
                .param(paymentId.toString()).query(String.class).list();
    }

    private long lastTransitionId() {
        return jdbc.sql("SELECT coalesce(max(id), 0) FROM payment_transitions").query(Long.class).single();
    }

    /** Outgoing payments that entered {@code REJECTED MS03} after the transition {@code id}. */
    private long rejectedMs03Since(long id) {
        return jdbc.sql("""
                        SELECT count(*) FROM payment_transitions t JOIN payments p USING (payment_id)
                        WHERE t.id > ? AND t.to_state = 'REJECTED' AND t.from_state <> 'REJECTED'
                          AND t.reason_code = 'MS03' AND p.direction = 'OUTGOING'""")
                .param(id).query(Long.class).single();
    }

    private List<String> cases(UUID paymentId) {
        return jdbc.sql("""
                        SELECT type || '|' || status || '|' || (details ->> 'action') FROM exception_cases
                        WHERE payment_id = ? ORDER BY opened_at""")
                .param(paymentId).query(String.class).list();
    }

    private int holdCalls(UUID paymentId) {
        return (int) LEDGER.findAll(postRequestedFor(urlEqualTo("/v1/holds"))).stream()
                .filter(call -> call.getBodyAsString().contains(paymentId.toString())).count();
    }

    private int settleCalls(UUID paymentId) {
        return LEDGER.findAll(postRequestedFor(urlEqualTo("/v1/holds/" + paymentId + "/settle-on-us"))).size();
    }

    private List<LoggedRequest> releaseCalls(UUID paymentId) {
        return LEDGER.findAll(postRequestedFor(urlEqualTo("/v1/holds/" + paymentId + "/release")));
    }

    private double rejected(String reason, String cause) {
        Counter counter = meters.find("tpe.payment.rejected")
                .tags("direction", "OUTGOING", "reason", reason, "cause", cause).counter();
        return counter == null ? 0 : counter.count();
    }

    /** An on-us payment: payer and payee at the own bank. */
    private UUID submit() {
        Map<String, Object> request = PaymentCommandIT.request(Ibans.own(), Ibans.own());
        String body = http().post().uri("/v1/payments").contentType(MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsString(request)).retrieve().body(String.class);
        return UUID.fromString(JSON.readTree(body).get("paymentId").asString());
    }
}
