package com.tpe.paymentorchestrator;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.CommandId;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import com.tpe.paymentorchestrator.application.payments.Payments;
import com.tpe.paymentorchestrator.application.runner.SagaRunner;
import com.tpe.paymentorchestrator.domain.payment.OutgoingCommand;
import com.tpe.paymentorchestrator.domain.payment.OutgoingTimings;
import com.tpe.paymentorchestrator.domain.payment.PayeeVerification;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentParty;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The saga runner and the outgoing {@code ASSESS_RISK} action against risk-service in WireMock
 * (specs/11-payment-orchestrator.md §6.2, §6.3; UC-04 2, 2a, 2b, 2c).
 */
class RiskAssessmentIT extends OrchestratorStack {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration AWAIT = Duration.ofSeconds(10);

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Payments payments;

    @Autowired
    SagaRunner runner;

    @Autowired
    MeterRegistry meters;

    @Test
    void anAllowedPaymentIsRiskCheckedWithItsVopResultAndWaitsForItsHold() {
        riskAnswers("allow");

        UUID paymentId = submit();

        await().atMost(AWAIT).untilAsserted(() -> assertThat(row(paymentId)).isEqualTo(
                new Row("RISK_CHECKED", null, "CREATE_HOLD", false)));
        assertThat(riskCalls(paymentId)).isEqualTo(1);
        assertThat(RISK.findAll(postRequestedFor(urlEqualTo("/v1/risk/assessments"))
                .withRequestBody(matchingJsonPath("$.paymentId", equalTo(paymentId.toString())))
                .withRequestBody(matchingJsonPath("$.vopResult", equalTo("CLOSE_MATCH")))
                .withRequestBody(matchingJsonPath("$.assessmentId", equalTo(paymentId + ":OUTGOING")))))
                .hasSize(1);
        assertThat(transitions(paymentId)).containsExactly("-|RECEIVED|SUBMITTED", "RECEIVED|RISK_CHECKED|RISK_ALLOW");
        assertThat(finalEvents(paymentId)).isEmpty();
    }

    @Test
    void ORC_AC_03_aRiskDenyRejectsFr01WithoutALedgerCallAndEmitsPaymentRejected() {
        riskAnswers("deny-fr01");
        double before = rejected("FR01", "BUSINESS");

        UUID paymentId = submit();

        await().atMost(AWAIT).untilAsserted(() -> assertThat(row(paymentId)).isEqualTo(
                new Row("REJECTED", "FR01", null, true)));
        // nothing to undo: no RELEASE_HOLD, so no ledger call follows
        assertThat(transitions(paymentId)).containsExactly("-|RECEIVED|SUBMITTED", "RECEIVED|REJECTED|RISK_DENY");
        assertThat(finalEvents(paymentId)).containsExactly("PaymentRejected|FR01");
        assertThat(rejected("FR01", "BUSINESS")).isEqualTo(before + 1);
    }

    @Test
    void ORC_AC_04_riskTimingOutTwiceRejectsFr01() {
        riskAnswers("timeout");
        double before = rejected("FR01", "TECHNICAL");

        UUID paymentId = submit();

        await().atMost(AWAIT).until(() -> riskCalls(paymentId) == 1);
        await().atMost(AWAIT).until(() -> jdbc.sql("SELECT action_attempts FROM payments WHERE payment_id = ?")
                .param(paymentId).query(Integer.class).single() == 1 && !leased(paymentId));
        // the retry is due 100 ms later; the sweeper hands it to the runner
        CLOCK.advance(Duration.ofMillis(150));
        await().atMost(AWAIT).untilAsserted(() -> assertThat(row(paymentId)).isEqualTo(
                new Row("REJECTED", "FR01", null, true)));
        assertThat(riskCalls(paymentId)).isEqualTo(2);
        assertThat(transitions(paymentId)).containsExactly("-|RECEIVED|SUBMITTED",
                "RECEIVED|REJECTED|RISK_UNAVAILABLE");
        assertThat(finalEvents(paymentId)).containsExactly("PaymentRejected|FR01");
        assertThat(rejected("FR01", "TECHNICAL")).isEqualTo(before + 1);
    }

    @Test
    void ORC_AC_21_aRiskReviewRejectsFr01WithoutALedgerCallAndEmitsPaymentRejected() {
        riskAnswers("review");

        UUID paymentId = submit();

        await().atMost(AWAIT).untilAsserted(() -> assertThat(row(paymentId)).isEqualTo(
                new Row("REJECTED", "FR01", null, true)));
        assertThat(transitions(paymentId)).containsExactly("-|RECEIVED|SUBMITTED", "RECEIVED|REJECTED|RISK_REVIEW");
        assertThat(finalEvents(paymentId)).containsExactly("PaymentRejected|FR01");
    }

    @Test
    void aSanctionsDenyRejectsRr04() {
        riskAnswers("deny-rr04");
        double before = rejected("RR04", "BUSINESS");

        UUID paymentId = submit();

        await().atMost(AWAIT).untilAsserted(() -> assertThat(row(paymentId)).isEqualTo(
                new Row("REJECTED", "RR04", null, true)));
        assertThat(finalEvents(paymentId)).containsExactly("PaymentRejected|RR04");
        assertThat(rejected("RR04", "BUSINESS")).isEqualTo(before + 1);
    }

    @Test
    void twoRunnersRacingOnOnePaymentMakeOneRiskCall() throws Exception {
        riskAnswers("allow");
        PaymentId paymentId = notYetDue();
        jdbc.sql("UPDATE payments SET next_action_at = ? WHERE payment_id = ?")
                .param(java.sql.Timestamp.from(CLOCK.instant())).param(paymentId.value()).update();

        // the sweeper competes as well
        CountDownLatch start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                threads.submit(() -> {
                    start.await();
                    runner.run(paymentId, false);
                    return null;
                });
            }
            start.countDown();
        }

        await().atMost(AWAIT).until(() -> "RISK_CHECKED".equals(row(paymentId.value()).state()));
        assertThat(riskCalls(paymentId.value())).isEqualTo(1);
    }

    @Test
    void theSweeperResumesAnActionWhoseLeaseExpired() {
        riskAnswers("allow");
        PaymentId paymentId = notYetDue();
        // a runner claimed the action and died: the lease runs 5 s
        jdbc.sql("UPDATE payments SET next_action_at = ?, action_attempts = 1 WHERE payment_id = ?")
                .param(java.sql.Timestamp.from(CLOCK.instant().plusSeconds(5))).param(paymentId.value()).update();

        CLOCK.advance(Duration.ofSeconds(6));

        await().atMost(AWAIT).until(() -> "RISK_CHECKED".equals(row(paymentId.value()).state()));
        assertThat(riskCalls(paymentId.value())).isEqualTo(1);
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
        return jdbc.sql("SELECT next_action_at > CAST(? AS TIMESTAMPTZ) + interval '1 second' FROM payments WHERE payment_id = ?")
                .param(java.sql.Timestamp.from(CLOCK.instant())).param(paymentId).query(Boolean.class).single();
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

    private int riskCalls(UUID paymentId) {
        return RISK.findAll(postRequestedFor(urlEqualTo("/v1/risk/assessments"))
                .withRequestBody(matchingJsonPath("$.paymentId", equalTo(paymentId.toString())))).size();
    }

    private double rejected(String reason, String cause) {
        Counter counter = meters.find("tpe.payment.rejected")
                .tags("direction", "OUTGOING", "reason", reason, "cause", cause).counter();
        return counter == null ? 0 : counter.count();
    }

    private UUID submit() {
        Map<String, Object> request = PaymentCommandIT.request(Ibans.own(), Ibans.peer());
        String body = http().post().uri("/v1/payments").contentType(MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsString(request)).retrieve().body(String.class);
        return UUID.fromString(JSON.readTree(body).get("paymentId").asString());
    }

    /** A {@code RECEIVED} payment whose {@code ASSESS_RISK} is not due yet: no runner or sweeper takes it. */
    private PaymentId notYetDue() {
        Instant acceptedAt = CLOCK.instant().plus(Duration.ofHours(1));
        var command = new OutgoingCommand(new CommandId(UUID.randomUUID()), "mobile-app", new EndToEndId("INV-1"),
                new PartyName("Anna Schmidt"), new Iban(Ibans.own()), new PartyName("Max Mustermann"),
                new Iban(Ibans.peer()), null, Money.parse("123.45"), null, "VOP-1", PayeeVerification.MATCH);
        Payment payment = Payment.submitted(new PaymentId(UUID.randomUUID()), command, "0".repeat(64),
                new TxId("TPE" + UUID.randomUUID().toString().replace("-", "").substring(0, 26).toUpperCase()),
                new Uetr(UUID.randomUUID().toString()),
                new PaymentParty(command.debtorName(), command.debtorIban(), new Bic("TPEBDEFFXXX")),
                new PaymentParty(command.creditorName(), command.creditorIban(), new Bic("PEERDEFFXXX")), false,
                acceptedAt, new OutgoingTimings(Duration.ofSeconds(10), Duration.ofSeconds(20),
                        Duration.ofSeconds(2)), null);
        assertThat(payments.insertIfNewCommand(payment, CLOCK.instant())).isTrue();
        return payment.paymentId();
    }
}
