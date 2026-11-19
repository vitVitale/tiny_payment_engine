package com.tpe.paymentorchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.tpe.contracts.AnswerSent;
import com.tpe.contracts.AnswerStatus;
import com.tpe.contracts.CreditTransferRejected;
import com.tpe.contracts.CreditTransferSettled;
import com.tpe.contracts.DecidedBy;
import com.tpe.contracts.Direction;
import com.tpe.contracts.SettlementSource;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentorchestrator.SagaProbe.Row;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Crash resume of every action with a remote call, outgoing and incoming (ADR-003, ADR-008, ORC-AC-13): the instance
 * dies after an action's remote call, before its result is applied; once the lease has expired a sweeper hands the
 * action over again. The payment ends as without the crash, the repeated call carries the same idempotency key, so
 * the ledger effect is the same, and there is one final event.
 */
class SagaResumeIT extends OrchestratorStack {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration AWAIT = Duration.ofSeconds(10);
    /** Past {@code tpe.runner.lease}, 5 s, and well before the hold give-up at 8 s. */
    private static final Duration PAST_LEASE = Duration.ofSeconds(6);
    private static final Bic PEER = new Bic("PEERDEFFXXX");

    @Autowired
    JdbcClient jdbc;

    private SagaProbe probe;

    @BeforeEach
    void probe() {
        probe = new SagaProbe(jdbc, CLOCK, RISK, LEDGER);
    }

    /**
     * An action to crash after, the ledger's answers, and how the payment ends.
     *
     * @param reason {@code null} for a settled payment
     */
    record Crash(PendingAction crashed, List<String> ledger, String state, String reason, String finalEvent) {

        @Override
        public String toString() {
            return "crash after " + crashed;
        }
    }

    static List<Crash> crashes() {
        List<String> settles = List.of("create-hold-created", "settle-on-us-settled");
        return List.of(
                new Crash(PendingAction.ASSESS_RISK, settles, "SETTLED", null, "PaymentSettled|"),
                new Crash(PendingAction.CREATE_HOLD, settles, "SETTLED", null, "PaymentSettled|"),
                new Crash(PendingAction.ASSESS_PAYEE_RISK, settles, "SETTLED", null, "PaymentSettled|"),
                new Crash(PendingAction.SETTLE_ON_US, settles, "SETTLED", null, "PaymentSettled|"),
                new Crash(PendingAction.RELEASE_HOLD,
                        List.of("create-hold-created", "settle-on-us-blocked-payee", "release-released"),
                        "REJECTED", "AC06", "PaymentRejected|AC06"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("crashes")
    void ORC_AC_13_aCrashAfterTheRemoteCallResumesToTheSameOutcome(Crash crash) {
        PendingAction crashed = crash.crashed();
        riskAnswers("allow");
        ledgerAnswers(crash.ledger().toArray(String[]::new));
        String payee = Ibans.own();
        ActionInterference.crashAfter(crashed, payee);

        UUID paymentId = submit(payee);

        // the crashed action keeps its claim until the lease expires
        await().atMost(AWAIT).untilAsserted(() -> {
            assertThat(probe.row(paymentId).pendingAction()).isEqualTo(crashed.name());
            assertThat(calls(crashed, paymentId)).hasSize(1);
            assertThat(probe.leased(paymentId)).as("leased").isTrue();
        });
        CLOCK.advance(PAST_LEASE);

        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId)).isEqualTo(
                new Row(crash.state(), crash.reason(), null, true)));
        assertThat(probe.finalEvents(paymentId)).containsExactly(crash.finalEvent());
        // the call was made again, the same one; every other call once
        assertThat(calls(crashed, paymentId)).hasSize(2);
        assertSameCall(calls(crashed, paymentId));
        for (PendingAction other : List.of(PendingAction.CREATE_HOLD, PendingAction.SETTLE_ON_US,
                PendingAction.RELEASE_HOLD)) {
            if (other != crashed) {
                assertThat(calls(other, paymentId)).hasSizeLessThanOrEqualTo(1);
            }
        }
    }

    /** The incoming actions to crash after, and how the payment ends. */
    static List<Crash> incomingCrashes() {
        List<String> settles = List.of("create-pending-credit-created", "confirm-pending-credit-confirmed");
        return List.of(
                new Crash(PendingAction.ASSESS_RISK, settles, "SETTLED", null, "PaymentSettled|"),
                new Crash(PendingAction.CREATE_PENDING_CREDIT, settles, "SETTLED", null, "PaymentSettled|"),
                new Crash(PendingAction.CONFIRM_CREDIT, settles, "SETTLED", null, "PaymentSettled|"),
                new Crash(PendingAction.CANCEL_CREDIT,
                        List.of("create-pending-credit-created", "cancel-pending-credit-cancelled"),
                        "REJECTED", "AB05", "PaymentRejected|AB05"));
    }

    /**
     * An incoming payment, received with an answer deadline past the lease: the crashed action of the verdict can
     * still be handed over before it (the gateway's own deadline is 5 s, the lease too). It is answered accepting and
     * settled by the CSM, or rejected by the CSM after the acceptance when the crash is in the cancel.
     */
    @ParameterizedTest(name = "incoming: {0}")
    @MethodSource("incomingCrashes")
    void ORC_AC_13_aCrashAfterAnIncomingRemoteCallResumesToTheSameOutcome(Crash crash) {
        PendingAction crashed = crash.crashed();
        riskAnswers("allow");
        ledgerAnswers(crash.ledger().toArray(String[]::new));
        String payee = Ibans.own();
        ActionInterference.crashAfter(crashed, payee);
        String txId = IncomingPaymentIT.txId();

        publishCsmEvent(IncomingPaymentIT.creditTransferReceived(PEER, txId, payee, Duration.ofSeconds(60)), txId,
                UUID.randomUUID());
        UUID paymentId = await().atMost(AWAIT).until(() -> probe.incoming(PEER.value(), txId),
                Optional::isPresent).orElseThrow();
        resumeIfCrashed(crashed, paymentId, PendingAction.ASSESS_RISK, PendingAction.CREATE_PENDING_CREDIT);
        await().atMost(AWAIT).until(() -> "ACCEPT_REQUESTED".equals(probe.row(paymentId).state()));
        publishCsmEvent(new AnswerSent(new TxId(txId), PEER, Direction.INCOMING, AnswerStatus.ACCP, null,
                DecidedBy.VERDICT, CLOCK.instant()), txId, UUID.randomUUID());
        await().atMost(AWAIT).until(() -> "ACCEPTED".equals(probe.row(paymentId).state()));
        if (crash.reason() == null) {
            publishCsmEvent(new CreditTransferSettled(new TxId(txId), PEER, Direction.INCOMING, null,
                    CLOCK.instant(), SettlementSource.STATUS_REPORT), txId, UUID.randomUUID());
        } else {
            publishCsmEvent(new CreditTransferRejected(new TxId(txId), PEER, Direction.INCOMING, null,
                    ReasonCode.parse(crash.reason()), CLOCK.instant(), SettlementSource.STATUS_REPORT), txId,
                    UUID.randomUUID());
        }
        resumeIfCrashed(crashed, paymentId, PendingAction.CONFIRM_CREDIT, PendingAction.CANCEL_CREDIT);

        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId)).isEqualTo(
                new Row(crash.state(), crash.reason(), null, true)));
        assertThat(probe.finalEvents(paymentId)).containsExactly(crash.finalEvent());
        assertThat(calls(crashed, paymentId)).hasSize(2);
        assertSameCall(calls(crashed, paymentId));
        for (PendingAction other : List.of(PendingAction.CREATE_PENDING_CREDIT, PendingAction.CONFIRM_CREDIT,
                PendingAction.CANCEL_CREDIT)) {
            if (other != crashed) {
                assertThat(calls(other, paymentId)).hasSizeLessThanOrEqualTo(1);
            }
        }
    }

    /**
     * If {@code crashed} is one of {@code actions}: waits until its one call is made and its claim held, then lets the
     * lease expire, so a sweeper hands it over again.
     */
    private void resumeIfCrashed(PendingAction crashed, UUID paymentId, PendingAction... actions) {
        if (!List.of(actions).contains(crashed)) {
            return;
        }
        await().atMost(AWAIT).untilAsserted(() -> {
            assertThat(probe.row(paymentId).pendingAction()).isEqualTo(crashed.name());
            assertThat(calls(crashed, paymentId)).hasSize(1);
            assertThat(probe.leased(paymentId)).as("leased").isTrue();
        });
        CLOCK.advance(PAST_LEASE);
    }

    @Test
    void aCrashAfterSettlingTheHoldResumesToOneSettlement() {
        riskAnswers("allow");
        ledgerAnswers("create-hold-created", "settle-hold-settled");
        String payee = Ibans.peer();
        ActionInterference.crashAfter(PendingAction.SETTLE_HOLD, payee);
        UUID paymentId = submit(payee);
        await().atMost(AWAIT).until(() -> "SENT_TO_CSM".equals(probe.row(paymentId).state()));
        String txId = probe.txId(paymentId);

        publishCsmEvent(new CreditTransferSettled(new TxId(txId), new Bic("TPEBDEFFXXX"), Direction.OUTGOING,
                new PaymentId(paymentId), CLOCK.instant(), SettlementSource.STATUS_REPORT), txId, UUID.randomUUID());

        await().atMost(AWAIT).untilAsserted(() -> {
            assertThat(probe.row(paymentId).pendingAction()).isEqualTo("SETTLE_HOLD");
            assertThat(probe.settleCalls(paymentId)).hasSize(1);
            assertThat(probe.leased(paymentId)).as("leased").isTrue();
        });
        CLOCK.advance(PAST_LEASE);

        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId)).isEqualTo(
                new Row("SETTLED", null, null, true)));
        assertThat(probe.finalEvents(paymentId)).containsExactly("PaymentSettled|");
        assertThat(probe.settleCalls(paymentId)).hasSize(2);
        assertSameCall(probe.settleCalls(paymentId));
    }

    /** Both calls are one request: the same key, the same body (the ledger replays its stored answer). */
    private static void assertSameCall(List<LoggedRequest> calls) {
        assertThat(calls).extracting(call -> call.getHeader("Idempotency-Key")).containsOnly(
                calls.getFirst().getHeader("Idempotency-Key"));
        assertThat(calls).extracting(call -> JSON.readTree(call.getBodyAsString())).containsOnly(
                JSON.readTree(calls.getFirst().getBodyAsString()));
    }

    private List<LoggedRequest> calls(PendingAction action, UUID paymentId) {
        Function<UUID, List<LoggedRequest>> calls = switch (action) {
            case ASSESS_RISK -> id -> probe.riskCalls(id).stream()
                    .filter(call -> call.getBodyAsString().contains(":" + direction(id))).toList();
            case ASSESS_PAYEE_RISK -> id -> probe.riskCalls(id).stream()
                    .filter(call -> call.getBodyAsString().contains(":INCOMING")).toList();
            case CREATE_HOLD -> probe::holdCalls;
            case SETTLE_ON_US -> probe::settleOnUsCalls;
            case RELEASE_HOLD -> probe::releaseCalls;
            case SETTLE_HOLD -> probe::settleCalls;
            case CREATE_PENDING_CREDIT -> probe::pendingCreditCalls;
            case CONFIRM_CREDIT -> probe::confirmCreditCalls;
            case CANCEL_CREDIT -> probe::cancelCreditCalls;
            case SEND -> throw new IllegalArgumentException("SEND makes no remote call");
        };
        return calls.apply(paymentId);
    }

    /** The payment's own direction: its {@code ASSESS_RISK} is assessed in it. */
    private String direction(UUID paymentId) {
        return jdbc.sql("SELECT direction FROM payments WHERE payment_id = ?").param(paymentId).query(String.class)
                .single();
    }

    /** A payment from the own bank to {@code payee}: on-us if the payee is at the own bank. */
    private UUID submit(String payee) {
        Map<String, Object> request = PaymentCommandIT.request(Ibans.own(), payee);
        String body = http().post().uri("/v1/payments").contentType(MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsString(request)).retrieve().body(String.class);
        return UUID.fromString(JSON.readTree(body).get("paymentId").asString());
    }
}
