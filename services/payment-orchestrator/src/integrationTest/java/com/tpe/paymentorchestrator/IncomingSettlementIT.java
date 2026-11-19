package com.tpe.paymentorchestrator;

import static com.tpe.paymentorchestrator.IncomingPaymentIT.creditTransferReceived;
import static com.tpe.paymentorchestrator.IncomingPaymentIT.txId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.contracts.AnswerSent;
import com.tpe.contracts.AnswerStatus;
import com.tpe.contracts.CreditTransferRejected;
import com.tpe.contracts.CreditTransferSettled;
import com.tpe.contracts.DecidedBy;
import com.tpe.contracts.Direction;
import com.tpe.contracts.SettlementSource;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentorchestrator.SagaProbe.Row;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An accepted incoming payment ends on the CSM's word (specs/11-payment-orchestrator.md §6.2, §6.5, UC-06 8a, 9): the
 * pending credit is confirmed to the payee when the CSM settles, cancelled when it rejects after our {@code ACCP};
 * contradicting facts open a case and move no money.
 */
class IncomingSettlementIT extends OrchestratorStack {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration AWAIT = Duration.ofSeconds(15);
    /** Long enough for the listener, the runner and the sweeper to have acted many times. */
    private static final Duration QUIET = Duration.ofSeconds(1);
    private static final Bic PEER = new Bic("PEERDEFFXXX");
    /** {@code journalEntryId} and {@code accountId} of {@code confirm-pending-credit-confirmed.json}. */
    private static final String ENTRY = "0192f0e4-7b1c-7cc3-a1b2-0000000000c1";
    private static final String PAYEE_ACCOUNT = "0192f0e4-7b1c-7cc3-a1b2-0000000000b2";

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
    void aSettlementThatOvertakesTheAcceptingAnswerSettlesAndTheLateAnswerChangesNothing() {
        String txId = txId();
        UUID paymentId = received(txId);
        await().atMost(AWAIT).until(() -> "ACCEPT_REQUESTED".equals(probe.row(paymentId).state()));

        settled(txId, SettlementSource.STATUS_REPORT);
        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId))
                .isEqualTo(new Row("SETTLED", null, null, true)));
        answer(txId, AnswerStatus.ACCP);

        await().during(QUIET).atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId))
                .isEqualTo(new Row("SETTLED", null, null, true)));
        assertThat(probe.cases(paymentId)).as("the late answer is no contradiction").isEmpty();
        assertThat(probe.transitions(paymentId)).endsWith("ACCEPT_REQUESTED|SETTLED|CREDIT_CONFIRMED");
        assertThat(probe.confirmCreditCalls(paymentId)).singleElement().satisfies(call ->
                assertThat(call.getHeader("Idempotency-Key")).isEqualTo("pending-credit-confirm:" + paymentId));
        assertThat(probe.finalEvents(paymentId)).containsExactly("PaymentSettled|");
        JsonNode event = JSON.readTree(finalEvent(paymentId));
        assertThat(event.get("direction").asString()).isEqualTo("INCOMING");
        assertThat(event.get("bookingEntryId").asString()).isEqualTo(ENTRY);
        assertThat(event.get("creditor").get("accountId").asString()).isEqualTo(PAYEE_ACCOUNT);
    }

    @Test
    void ORC_AC_14_anIncomingTransferIsAcceptedOnTheAnswerAndSettledOnTheCsmsWord() {
        String txId = txId();
        UUID paymentId = received(txId);
        await().atMost(AWAIT).until(() -> "ACCEPT_REQUESTED".equals(probe.row(paymentId).state()));

        answer(txId, AnswerStatus.ACCP);
        await().atMost(AWAIT).until(() -> "ACCEPTED".equals(probe.row(paymentId).state()));
        assertThat(probe.confirmCreditCalls(paymentId)).as("not before the CSM settles").isEmpty();
        settled(txId, SettlementSource.STATUS_REPORT);

        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId))
                .isEqualTo(new Row("SETTLED", null, null, true)));
        assertThat(probe.transitions(paymentId)).containsExactly("-|RECEIVED|CSM_RECEIVED",
                "RECEIVED|ACCEPT_REQUESTED|PENDING_CREDIT_CREATED", "ACCEPT_REQUESTED|ACCEPTED|ANSWER_ACCP",
                "ACCEPTED|SETTLED|CREDIT_CONFIRMED");
        assertThat(probe.finalEvents(paymentId)).containsExactly("PaymentSettled|");
    }

    @Test
    void aSettlementOnceRecordedIsANoOpWhateverItsSource() {
        String txId = txId();
        UUID paymentId = acceptedPayment(txId);

        settled(txId, SettlementSource.STATUS_REPORT);
        await().atMost(AWAIT).until(() -> "SETTLED".equals(probe.row(paymentId).state()));
        settled(txId, SettlementSource.INVESTIGATION);

        await().during(QUIET).atMost(AWAIT).untilAsserted(() -> assertThat(probe.confirmCreditCalls(paymentId))
                .hasSize(1));
        assertThat(probe.finalEvents(paymentId)).containsExactly("PaymentSettled|");
        assertThat(probe.cases(paymentId)).isEmpty();
    }

    @Test
    void ORC_AC_25_aRejectionAfterOurAcceptanceRejectsWithTheReasonAndCancelsTheCredit() {
        String txId = txId();
        UUID paymentId = acceptedPayment(txId);
        assertThat(probe.timers(paymentId)).as("scheduled by AnswerSent(ACCP)")
                .containsExactly("SETTLEMENT_OVERDUE|SCHEDULED");

        rejected(txId, ReasonCode.AB05);

        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId))
                .isEqualTo(new Row("REJECTED", "AB05", null, true)));
        assertThat(probe.transitions(paymentId)).endsWith("ACCEPTED|REJECTED|CSM_REJECTED");
        assertThat(probe.cancelCreditCalls(paymentId)).hasSize(1);
        assertThat(probe.confirmCreditCalls(paymentId)).isEmpty();
        assertThat(probe.finalEvents(paymentId)).containsExactly("PaymentRejected|AB05");
        assertThat(probe.timers(paymentId)).as("recording the outcome cancels the timers")
                .containsExactly("SETTLEMENT_OVERDUE|CANCELLED");
        assertThat(jdbc.sql("SELECT csm_outcome FROM payments WHERE payment_id = ?").param(paymentId)
                .query(String.class).single()).isEqualTo("REJECTED");
    }

    @Test
    void aSettlementAfterTheRejectionOpensACaseAndMakesNoLedgerCall() {
        String txId = txId();
        UUID paymentId = acceptedPayment(txId);
        rejected(txId, ReasonCode.AB05);
        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId).finalEventEmitted()).isTrue());

        settled(txId, SettlementSource.INVESTIGATION);

        await().atMost(AWAIT).until(() -> probe.cases(paymentId).contains("CONTRADICTING_CSM_OUTCOME|OPEN"));
        await().during(QUIET).atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId))
                .isEqualTo(new Row("REJECTED", "AB05", null, true)));
        assertThat(probe.confirmCreditCalls(paymentId)).isEmpty();
        assertThat(probe.finalEvents(paymentId)).containsExactly("PaymentRejected|AB05");
    }

    @Test
    void aCancelledCreditTheCsmSettledOpensACaseWithNoFinalEvent() {
        ledgerAnswers("create-pending-credit-created", "confirm-pending-credit-cancelled",
                "cancel-pending-credit-cancelled");
        String txId = txId();
        UUID paymentId = acceptedPayment(txId);

        settled(txId, SettlementSource.STATUS_REPORT);

        await().atMost(AWAIT).until(() -> probe.cases(paymentId).contains("CONTRADICTING_CSM_OUTCOME|OPEN"));
        assertThat(probe.row(paymentId)).isEqualTo(new Row("ACCEPTED", null, null, false));
        assertThat(probe.finalEvents(paymentId)).isEmpty();
    }

    @Test
    void anIncomingOutcomeForNoPaymentOfOursOpensACaseWithoutAPayment() {
        String txId = txId();

        settled(txId, SettlementSource.STATUS_REPORT);

        await().atMost(AWAIT).until(() -> probe.casesWithoutPayment().stream().anyMatch(case_ -> case_.contains(txId)));
        assertThat(probe.casesWithoutPayment()).filteredOn(details -> details.contains(txId)).singleElement()
                .satisfies(details -> assertThat(details).contains("\"cause\": \"UNKNOWN_TX\"",
                        "\"type\": \"CreditTransferSettled\""));
    }

    /** Received now and answered accepting: {@code ACCEPTED}, its pending credit created. */
    private UUID acceptedPayment(String txId) {
        UUID paymentId = received(txId);
        await().atMost(AWAIT).until(() -> "ACCEPT_REQUESTED".equals(probe.row(paymentId).state()));
        answer(txId, AnswerStatus.ACCP);
        await().atMost(AWAIT).until(() -> "ACCEPTED".equals(probe.row(paymentId).state()));
        return paymentId;
    }

    private UUID received(String txId) {
        publishCsmEvent(creditTransferReceived(PEER, txId, Ibans.own()), txId, UUID.randomUUID());
        return await().atMost(AWAIT).until(() -> probe.incoming(PEER.value(), txId), Optional::isPresent)
                .orElseThrow();
    }

    private void answer(String txId, AnswerStatus status) {
        publishCsmEvent(new AnswerSent(new TxId(txId), PEER, Direction.INCOMING, status, null, DecidedBy.VERDICT,
                CLOCK.instant()), txId, UUID.randomUUID());
    }

    private void settled(String txId, SettlementSource source) {
        publishCsmEvent(new CreditTransferSettled(new TxId(txId), PEER, Direction.INCOMING, null, CLOCK.instant(),
                source), txId, UUID.randomUUID());
    }

    private void rejected(String txId, ReasonCode reason) {
        publishCsmEvent(new CreditTransferRejected(new TxId(txId), PEER, Direction.INCOMING, null, reason,
                CLOCK.instant(), SettlementSource.STATUS_REPORT), txId, UUID.randomUUID());
    }

    private String finalEvent(UUID paymentId) {
        return jdbc.sql("SELECT payload::text FROM outbox WHERE topic = 'payments.events' AND message_key = ?")
                .param(paymentId.toString()).query(String.class).single();
    }
}
