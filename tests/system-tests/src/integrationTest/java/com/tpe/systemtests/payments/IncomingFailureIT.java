package com.tpe.systemtests.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import com.tpe.contracts.ContractJson;
import com.tpe.contracts.Direction;
import com.tpe.contracts.PaymentRejected;
import com.tpe.contracts.PaymentSettled;
import com.tpe.invariantchecker.CsmInvariants.Answer;
import com.tpe.invariantchecker.CsmInvariants.SimulatorMessage;
import com.tpe.systemtests.harness.Bounds;
import com.tpe.systemtests.harness.KafkaProduceProxy;
import com.tpe.systemtests.harness.LocalDatabases;
import com.tpe.systemtests.harness.PaymentSystemTest;
import com.tpe.systemtests.harness.ServiceProcess;
import com.tpe.systemtests.harness.TopicRecorder;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The failures of an incoming payment around and after our answer, end to end (specs/20-testing-strategy.md §4;
 * UC-06 6b, 8a, 8b, NFR): a verdict too late for the answer deadline, the CSM rejecting after our {@code ACCP}, a
 * settlement the CSM never confirms, and the non-critical services down. The per-test invariants (INV-4, -6, -7, -10,
 * -11 for the incoming side, beside the ledger's) run after each scenario; the webhook outcomes (INV-13: "not
 * notified" of I-07, the webhooks after the restart of I-13) come with the notifications track. I-06 slows the produce
 * of every orchestrator and I-13 stops the shared vop-service, so the class runs alone.
 */
@Isolated
@EnabledIfSystemProperty(named = "tpe.local-stack", matches = "true",
        disabledReason = "needs the compose stack; run with -Dtpe.local-stack=true")
class IncomingFailureIT extends PaymentSystemTest {

    private static final String AMOUNT = "12.34";
    private static final long AMOUNT_MINOR = 1_234;
    private static final String PACS_002 = "pacs.002.001.10";
    private static final String PACS_028 = "pacs.028.001.03";
    private static final String PEER_BIC = "PEERDEFFXXX";

    /**
     * Everything the orchestrator produces is held back past the gateway's answer deadline and transmit window
     * (Toxiproxy between its producer and Kafka; it consumes directly), so its {@code ACCP} verdict comes late: the
     * gateway answers {@code RJCT AB05} itself at the deadline, ignores the late verdict, and the orchestrator, told by
     * {@code AnswerSent}, rejects the payment and cancels the pending credit it had created. A technical refusal of
     * ours (25 §1).
     */
    @Test
    void E2E_I_06_aVerdictLaterThanTheAnswerDeadlineIsIgnoredAndTheGatewayRejectsAb05() throws SQLException {
        Party payee = customer(0);
        RejectedCount rejected = rejectedIncoming("AB05");
        KafkaProduceProxy kafka = KafkaProduceProxy.get();
        IncomingTransfer transfer;
        kafka.delayProduce(Bounds.verdictHeldPastAnswer());
        try {
            transfer = received(payee.iban(), AMOUNT, "ACCEPT");
            await().atMost(Bounds.verdictHeldPastAnswer().plus(Bounds.orchestratorEventDelivery())).untilAsserted(() ->
                    assertThat(gatewayNotes(transfer.txId())).as("the late verdict").contains("verdict ignored"));
        } finally {
            kafka.heal();
        }

        assertThat(gatewayIncoming(PEER_BIC, transfer.txId())).isEqualTo("RJCT AB05 DEADLINE DELIVERED");
        assertThat(simulator().answers(transfer.txId())).singleElement().satisfies(answer -> {
            assertThat(answer.status()).isEqualTo("RJCT");
            assertThat(answer.reason()).isEqualTo("AB05");
        });
        assertThat(finalState(transfer.payment(), Bounds.paymentFinal())).isEqualTo("REJECTED");
        assertThat(orchestratorPayment(transfer.payment()).reasonCode()).isEqualTo("AB05");
        assertThat(states(transfer.payment())).containsExactly("RECEIVED", "ACCEPT_REQUESTED", "REJECTED");
        assertPendingCreditCancelled(transfer.payment());
        assertNotCredited(payee, transfer);
        rejected.assertTaggedOnce("TECHNICAL");
        assertRejectedEvent(transfer, "AB05");
    }

    /**
     * {@code REJECT_AFTER_ACCP} (17 §3.2): our {@code ACCP} reached the CSM, which then rejects the payment
     * {@code RJCT AB05} without settling it. The pending credit is cancelled and the payee never credited.
     */
    @Test
    void E2E_I_07_aRejectionByTheCsmAfterOurAccpCancelsThePendingCredit() throws SQLException {
        Party payee = customer(0);

        IncomingTransfer transfer = received(payee.iban(), AMOUNT, "REJECT_AFTER_ACCP");

        assertThat(finalState(transfer.payment(), Bounds.paymentFinal())).isEqualTo("REJECTED");
        assertThat(orchestratorPayment(transfer.payment()).reasonCode()).isEqualTo("AB05");
        assertThat(states(transfer.payment())).containsExactly("RECEIVED", "ACCEPT_REQUESTED", "ACCEPTED",
                "REJECTED");
        assertThat(simulator().answers(transfer.txId())).extracting(Answer::status).containsExactly("ACCP");
        assertThat(simulator().sentEnvelopes(transfer.txId(), PACS_002)).singleElement()
                .satisfies(report -> assertThat(report).contains("<TxSts>RJCT</TxSts>", "<Cd>AB05</Cd>"));
        assertPendingCreditCancelled(transfer.payment());
        assertNotCredited(payee, transfer);
        assertRejectedEvent(transfer, "AB05");
    }

    /**
     * {@code NO_CONFIRMATION} (17 §3.2): the CSM settles after our {@code ACCP} but sends no {@code pacs.002}. While
     * the payment waits for it, the payee is not credited; once {@code tpe.incoming.settlement-overdue} has passed, the
     * orchestrator investigates ({@code pacs.028}), the CSM answers {@code ACSC}, and only then is the payee credited.
     */
    @Test
    void E2E_I_08_aSettlementWithoutAConfirmationIsInvestigatedAndCreditedOnlyThen() throws SQLException {
        Party payee = customer(0);

        IncomingTransfer transfer = received(payee.iban(), AMOUNT, "NO_CONFIRMATION");
        await().atMost(Bounds.answerOverdue()).untilAsserted(() ->
                assertThat(simulator().answers(transfer.txId())).extracting(Answer::status).containsExactly("ACCP"));
        // settled at the CSM, unconfirmed: well before settlement-overdue, nothing is booked
        assertThat(booked(payee)).as("credited before the settlement is known").isZero();
        assertThat(pendingCredit(transfer.payment()).body().get("status").asString()).isEqualTo("ACTIVE");

        assertThat(finalState(transfer.payment(), Bounds.incomingSettledAfterInvestigation())).isEqualTo("SETTLED");
        assertThat(states(transfer.payment())).containsExactly("RECEIVED", "ACCEPT_REQUESTED", "ACCEPTED",
                "INVESTIGATING", "SETTLED");
        assertThat(simulator().received(transfer.txId())).extracting(SimulatorMessage::kind,
                SimulatorMessage::messageType).contains(tuple("RECEIVED", PACS_028));
        assertThat(simulator().sentEnvelopes(transfer.txId(), PACS_002)).as("the CSM's answer to the pacs.028")
                .singleElement().satisfies(report -> assertThat(report).contains("<TxSts>ACSC</TxSts>"));
        await().atMost(Bounds.eventDelivery()).untilAsserted(() ->
                assertThat(postings(payee.account(), transfer.payment())).containsExactly("PAYMENT_IN CR "
                        + AMOUNT_MINOR));
        // the ACSC answers the pacs.028, which the investigation sent: a credit after its start is one after the ACSC
        assertThat(creditedAt(transfer.payment())).as("credited only after the investigation began")
                .isAfterOrEqualTo(transitionedAt(transfer.payment(), "INVESTIGATING"));
        assertThat(booked(payee)).isEqualTo(AMOUNT_MINOR);
        assertThat(csmSettlements(transfer.payment())).singleElement()
                .satisfies(settlement -> assertThat(settlement.direction()).isEqualTo("INCOMING"));
        assertThat(finalEvents(transfer.payment())).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("PaymentSettled");
            PaymentSettled settled = new ContractJson().read(event.value(), PaymentSettled.class);
            assertThat(settled.direction()).isEqualTo(Direction.INCOMING);
        });
    }

    /**
     * UC-06 NFR: the incoming path does not depend on vop-service or notification-service. With vop-service stopped
     * (notification-service is not part of the harness until the notifications track), an incoming payment is
     * answered by our verdict within the answer deadline, not by the gateway's, and settled and credited. The webhook
     * delivery after the restart comes with the notifications track.
     */
    @Test
    void E2E_I_13_anIncomingPaymentIsAnsweredAndSettledWithoutVopAndNotifications() throws SQLException {
        Party payee = customer(0);
        ServiceProcess vop = vopServiceProcess();
        IncomingTransfer transfer;
        vop.stop();
        try {
            transfer = received(payee.iban(), AMOUNT, "ACCEPT");

            assertThat(finalState(transfer.payment(), Bounds.paymentFinal())).isEqualTo("SETTLED");
        } finally {
            vop.start();
        }

        assertThat(gatewayIncoming(PEER_BIC, transfer.txId())).isEqualTo("ACCP - VERDICT DELIVERED");
        await().atMost(Bounds.eventDelivery()).untilAsserted(() ->
                assertThat(postings(payee.account(), transfer.payment())).containsExactly("PAYMENT_IN CR "
                        + AMOUNT_MINOR));
        assertThat(booked(payee)).isEqualTo(AMOUNT_MINOR);
    }

    /** The pending credit was created, then cancelled (INV-4 checks it after the test as well). */
    private static void assertPendingCreditCancelled(UUID payment) {
        await().atMost(Bounds.eventDelivery()).untilAsserted(() -> {
            var credit = pendingCredit(payment);
            assertThat(credit.status()).as("%s", credit.body()).isEqualTo(200);
            assertThat(credit.body().get("status").asString()).isEqualTo("CANCELLED");
        });
    }

    /** Nothing booked for the payee, nothing settled at the CSM or on the CSM settlement account. */
    private void assertNotCredited(Party payee, IncomingTransfer transfer) throws SQLException {
        assertThat(postings(payee.account(), transfer.payment())).isEmpty();
        assertThat(booked(payee)).isZero();
        assertThat(csmSettlements(transfer.payment())).isEmpty();
        assertThat(csmSettlementPostings(transfer.payment())).isEmpty();
    }

    /** One {@code PaymentRejected} of the payment, incoming, with {@code reason}. */
    private static void assertRejectedEvent(IncomingTransfer transfer, String reason) {
        assertThat(finalEvents(transfer.payment())).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("PaymentRejected");
            PaymentRejected rejected = new ContractJson().read(event.value(), PaymentRejected.class);
            assertThat(rejected.direction()).isEqualTo(Direction.INCOMING);
            assertThat(rejected.reasonCode().code()).isEqualTo(reason);
        });
    }

    /** The states the payment went through, oldest first. */
    private static List<String> states(UUID payment) throws SQLException {
        return transitions(payment).stream().map(transition -> transition.split("\\|")[1]).toList();
    }

    /** When the orchestrator moved the payment to {@code state}. */
    private static Instant transitionedAt(UUID payment, String state) throws SQLException {
        try (var orchestrator = LocalDatabases.payments(); var statement = orchestrator.prepareStatement(
                "SELECT min(at) FROM payment_transitions WHERE payment_id = ? AND to_state = ?")) {
            statement.setObject(1, payment);
            statement.setString(2, state);
            try (var rs = statement.executeQuery()) {
                rs.next();
                return rs.getTimestamp(1).toInstant();
            }
        }
    }

    /** When the ledger booked the payment's {@code PAYMENT_IN} entry. */
    private static Instant creditedAt(UUID payment) throws SQLException {
        try (var ledger = LocalDatabases.ledger(); var statement = ledger.prepareStatement(
                "SELECT min(created_at) FROM journal_entries WHERE payment_id = ? AND entry_type = 'PAYMENT_IN'")) {
            statement.setObject(1, payment);
            try (var rs = statement.executeQuery()) {
                rs.next();
                return rs.getTimestamp(1).toInstant();
            }
        }
    }

    /** The payment's final events, once one has arrived. */
    private static List<TopicRecorder.Recorded> finalEvents(UUID payment) {
        await().atMost(Bounds.eventDelivery())
                .until(() -> !topics().records(TopicRecorder.PAYMENTS_EVENTS, payment.toString()).isEmpty());
        return topics().records(TopicRecorder.PAYMENTS_EVENTS, payment.toString());
    }
}
