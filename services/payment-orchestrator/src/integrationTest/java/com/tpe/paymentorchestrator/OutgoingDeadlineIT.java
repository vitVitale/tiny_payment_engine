package com.tpe.paymentorchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.paymentorchestrator.SagaProbe.Row;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code OUTGOING_DEADLINE} timer (specs/11-payment-orchestrator.md §6.4, §6.6, ADR-008): no outgoing payment
 * outlives the scheme deadline before it reaches the CSM, an on-us payment holding its funds has none, and a timer
 * racing an action in flight ends the payment once.
 */
class OutgoingDeadlineIT extends OrchestratorStack {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration AWAIT = Duration.ofSeconds(10);
    /** Past {@code deadline_at}: 20 s after acceptance. */
    private static final Duration PAST_DEADLINE = Duration.ofSeconds(21);

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MeterRegistry meters;

    private SagaProbe probe;

    @BeforeEach
    void probe() {
        probe = new SagaProbe(jdbc, CLOCK, RISK, LEDGER);
    }

    @Test
    void ORC_AC_23_anOnUsSettlementInFlightAtTheDeadlineIsLeftToSettle() {
        riskAnswers("allow");
        ledgerAnswers("create-hold-created", "settle-on-us-settled", "release-released");
        String payee = Ibans.own();
        ActionInterference.holdAfter(PendingAction.SETTLE_ON_US, payee);

        UUID paymentId = submit(payee);

        await().atMost(AWAIT).until(() -> ActionInterference.held() >= 1);
        CLOCK.advance(PAST_DEADLINE);
        await().atMost(AWAIT).until(() -> probe.timers(paymentId).equals(List.of("OUTGOING_DEADLINE|FIRED")));
        assertThat(probe.row(paymentId)).isEqualTo(new Row("FUNDS_RESERVED", null, "SETTLE_ON_US", false));
        ActionInterference.releaseHeld();

        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId)).isEqualTo(
                new Row("SETTLED", null, null, true)));
        assertThat(probe.transitions(paymentId)).noneMatch(transition -> transition.endsWith("|DEADLINE"));
        assertThat(probe.releaseCalls(paymentId)).isEmpty();
        assertThat(probe.finalEvents(paymentId)).containsExactly("PaymentSettled|");
    }

    @Test
    void aDeadlineInReceivedRejectsAb03WhileTheRiskAnswerIsDiscardedAndNoLedgerCallIsMade() {
        riskAnswers("allow");
        ledgerAnswers("create-hold-created", "release-released");
        String payee = Ibans.own();
        ActionInterference.holdAfter(PendingAction.ASSESS_RISK, payee);
        double fired = counter("tpe.timer.fired", "type", "OUTGOING_DEADLINE");
        double deadlineRejects = counter("tpe.deadline.reject", "reason", "AB03");
        long transitionsBefore = probe.lastTransitionId();

        UUID paymentId = submit(payee);

        await().atMost(AWAIT).until(() -> ActionInterference.held() >= 1);
        CLOCK.advance(PAST_DEADLINE);
        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId)).isEqualTo(
                new Row("REJECTED", "AB03", null, true)));
        int held = ActionInterference.held();
        ActionInterference.releaseHeld();
        // the risk answers come back after the timer: their results are discarded
        await().atMost(AWAIT).until(() -> ActionInterference.left() >= held);

        assertThat(probe.row(paymentId)).isEqualTo(new Row("REJECTED", "AB03", null, true));
        assertThat(probe.transitions(paymentId)).containsExactly("-|RECEIVED|SUBMITTED", "RECEIVED|REJECTED|DEADLINE");
        assertThat(probe.finalEvents(paymentId)).containsExactly("PaymentRejected|AB03");
        assertThat(probe.holdCalls(paymentId)).isEmpty();
        assertThat(probe.releaseCalls(paymentId)).isEmpty();
        // moving the clock fires the deadlines other tests left, too: each counts once
        await().atMost(AWAIT).untilAsserted(() -> assertThat(counter("tpe.deadline.reject", "reason", "AB03"))
                .isEqualTo(deadlineRejects + probe.transitionsSince(transitionsBefore, "DEADLINE")));
        assertThat(counter("tpe.timer.fired", "type", "OUTGOING_DEADLINE")).isGreaterThan(fired);
    }

    @Test
    void aHeldPaymentToAnotherBankAtItsDeadlineIsRejectedAb03AndItsHoldReleased() {
        riskAnswers("allow");
        ledgerAnswers("create-hold-created", "release-released");

        // until M3 sends it, a payment to another bank waits for SEND
        UUID paymentId = submit(Ibans.peer());
        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId)).isEqualTo(
                new Row("FUNDS_RESERVED", null, "SEND", false)));

        CLOCK.advance(PAST_DEADLINE);

        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId)).isEqualTo(
                new Row("REJECTED", "AB03", null, true)));
        assertThat(probe.transitions(paymentId)).endsWith("FUNDS_RESERVED|REJECTED|DEADLINE");
        assertThat(probe.releaseCalls(paymentId)).hasSize(1);
        assertThat(probe.finalEvents(paymentId)).containsExactly("PaymentRejected|AB03");
        assertThat(probe.timers(paymentId)).containsExactly("OUTGOING_DEADLINE|FIRED");
    }

    @Test
    void aSettledPaymentsDeadlineIsANoOp() {
        riskAnswers("allow");
        ledgerAnswers("create-hold-created", "settle-on-us-settled");

        UUID paymentId = submit(Ibans.own());
        await().atMost(AWAIT).until(() -> "SETTLED".equals(probe.row(paymentId).state()));

        CLOCK.advance(PAST_DEADLINE);

        await().atMost(AWAIT).until(() -> probe.timers(paymentId).equals(List.of("OUTGOING_DEADLINE|FIRED")));
        assertThat(probe.row(paymentId)).isEqualTo(new Row("SETTLED", null, null, true));
        assertThat(probe.finalEvents(paymentId)).containsExactly("PaymentSettled|");
    }

    private double counter(String name, String tag, String value) {
        Counter counter = meters.find(name).tag(tag, value).counter();
        return counter == null ? 0 : counter.count();
    }

    private UUID submit(String creditorIban) {
        Map<String, Object> request = PaymentCommandIT.request(Ibans.own(), creditorIban);
        String body = http().post().uri("/v1/payments").contentType(MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsString(request)).retrieve().body(String.class);
        return UUID.fromString(JSON.readTree(body).get("paymentId").asString());
    }
}
