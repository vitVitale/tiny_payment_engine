package com.tpe.paymentorchestrator;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What a component test reads of one payment: its row, transitions, timers and outbox rows, and the calls risk-service
 * and ledger-service received for it.
 */
final class SagaProbe {

    /** The payment row: state, reason, pending action, final event written. */
    record Row(String state, String reasonCode, String pendingAction, boolean finalEventEmitted) {}

    private final JdbcClient jdbc;
    private final Clock clock;
    private final WireMockServer risk;
    private final WireMockServer ledger;

    SagaProbe(JdbcClient jdbc, Clock clock, WireMockServer risk, WireMockServer ledger) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.risk = risk;
        this.ledger = ledger;
    }

    Row row(UUID paymentId) {
        return jdbc.sql("SELECT state, reason_code, pending_action, final_event_emitted FROM payments "
                        + "WHERE payment_id = ?")
                .param(paymentId)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)))
                .single();
    }

    /** The pending action is claimed: its lease runs longer than a back-off would. */
    boolean leased(UUID paymentId) {
        return jdbc.sql("""
                        SELECT next_action_at > CAST(? AS TIMESTAMPTZ) + interval '1 second'
                        FROM payments WHERE payment_id = ?""")
                .param(Timestamp.from(clock.instant())).param(paymentId).query(Boolean.class).single();
    }

    /** {@code from|to|cause}, {@code -} for the first. */
    List<String> transitions(UUID paymentId) {
        return jdbc.sql("""
                        SELECT coalesce(from_state, '-') || '|' || to_state || '|' || cause
                        FROM payment_transitions WHERE payment_id = ? ORDER BY id""")
                .param(paymentId).query(String.class).list();
    }

    /** {@code type|status}. */
    List<String> timers(UUID paymentId) {
        return jdbc.sql("SELECT type || '|' || status FROM payment_timers WHERE payment_id = ? ORDER BY timer_id")
                .param(paymentId).query(String.class).list();
    }

    /** {@code PaymentSettled|} or {@code PaymentRejected|<reason>}, in order. */
    List<String> finalEvents(UUID paymentId) {
        return jdbc.sql("""
                        SELECT message_type || '|' || coalesce(payload ->> 'reasonCode', '') FROM outbox
                        WHERE topic = 'payments.events' AND message_key = ? ORDER BY id""")
                .param(paymentId.toString()).query(String.class).list();
    }

    long lastTransitionId() {
        return jdbc.sql("SELECT coalesce(max(id), 0) FROM payment_transitions").query(Long.class).single();
    }

    /** Transitions with {@code cause} recorded after the transition {@code id}, of any payment. */
    long transitionsSince(long id, String cause) {
        return jdbc.sql("SELECT count(*) FROM payment_transitions WHERE id > ? AND cause = ?")
                .param(id).param(cause).query(Long.class).single();
    }

    List<LoggedRequest> riskCalls(UUID paymentId) {
        return risk.findAll(postRequestedFor(urlEqualTo("/v1/risk/assessments"))
                .withRequestBody(matchingJsonPath("$.paymentId", equalTo(paymentId.toString()))));
    }

    List<LoggedRequest> holdCalls(UUID paymentId) {
        return ledger.findAll(postRequestedFor(urlEqualTo("/v1/holds"))
                .withRequestBody(matchingJsonPath("$.holdId", equalTo(paymentId.toString()))));
    }

    List<LoggedRequest> settleOnUsCalls(UUID paymentId) {
        return ledger.findAll(postRequestedFor(urlEqualTo("/v1/holds/" + paymentId + "/settle-on-us")));
    }

    List<LoggedRequest> releaseCalls(UUID paymentId) {
        return ledger.findAll(postRequestedFor(urlEqualTo("/v1/holds/" + paymentId + "/release")));
    }
}
