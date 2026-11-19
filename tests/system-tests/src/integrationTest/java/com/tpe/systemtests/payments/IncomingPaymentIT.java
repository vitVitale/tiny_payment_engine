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
import com.tpe.systemtests.harness.PaymentSystemTest;
import com.tpe.systemtests.harness.Response;
import com.tpe.systemtests.harness.TestData;
import com.tpe.systemtests.harness.TopicRecorder;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Payments from another PSP end to end (specs/20-testing-strategy.md §4; UC-06): the CSM (csm-simulator) delivers a
 * {@code pacs.008} to csm-gateway, the orchestrator assesses it, records the pending credit and answers through the
 * gateway, the CSM settles and confirms, and the ledger credits the payee. The per-test invariants (INV-4, -6, -7, -8,
 * -10, -11 for the incoming side, beside the ledger's) run after each scenario; the webhook outcomes (INV-13) come with
 * the notifications track.
 */
@EnabledIfSystemProperty(named = "tpe.local-stack", matches = "true",
        disabledReason = "needs the compose stack; run with -Dtpe.local-stack=true")
class IncomingPaymentIT extends PaymentSystemTest {

    private static final String AMOUNT = "12.34";
    private static final long AMOUNT_MINOR = 1_234;
    private static final String PACS_002 = "pacs.002.001.10";

    /**
     * To an open account ({@code ACCEPT}): our {@code ACCP}, then the CSM's {@code ACSC}; the payee credited by a
     * {@code PAYMENT_IN} entry whose debit is the CSM settlement account; the pending credit confirmed; the CSM's
     * settlement log and the ledger agree; one {@code PaymentSettled}, incoming.
     */
    @Test
    @Tag("smoke")
    void E2E_I_01_anIncomingPaymentToAnOpenAccountIsAcceptedSettledAndCredited() throws SQLException {
        Party payee = customer(0);

        IncomingTransfer transfer = received(payee.iban(), AMOUNT, "ACCEPT");

        assertThat(finalState(transfer.payment(), Bounds.paymentFinal())).isEqualTo("SETTLED");
        assertThat(transitions(transfer.payment())).containsExactly("-|RECEIVED|CSM_RECEIVED",
                "RECEIVED|ACCEPT_REQUESTED|PENDING_CREDIT_CREATED", "ACCEPT_REQUESTED|ACCEPTED|ANSWER_ACCP",
                "ACCEPTED|SETTLED|CREDIT_CONFIRMED");

        // our ACCP, then the CSM's settlement confirmation
        assertThat(simulator().answers(transfer.txId())).extracting(Answer::status).containsExactly("ACCP");
        assertThat(simulator().received(transfer.txId())).extracting(SimulatorMessage::kind,
                SimulatorMessage::messageType).containsSubsequence(
                        tuple("SENT", "pacs.008.001.08"),
                        tuple("RECEIVED", PACS_002),
                        tuple("SENT", PACS_002));
        assertThat(simulator().sentEnvelopes(transfer.txId(), PACS_002)).singleElement()
                .satisfies(confirmation -> assertThat(confirmation).contains("<TxSts>ACSC</TxSts>"));

        // the payee credited from the CSM settlement account (INV-8 checks that side after the test)
        await().atMost(Bounds.eventDelivery()).untilAsserted(() ->
                assertThat(postings(payee.account(), transfer.payment())).containsExactly("PAYMENT_IN CR "
                        + AMOUNT_MINOR));
        assertThat(csmSettlementPostings(transfer.payment())).as("one DR CSM_SETTLEMENT")
                .containsExactly("DR " + AMOUNT_MINOR);
        assertThat(booked(payee)).isEqualTo(AMOUNT_MINOR);
        assertThat(available(payee)).isEqualTo(AMOUNT_MINOR);
        assertThat(pendingCredit(transfer.payment()).body().get("status").asString()).isEqualTo("CONFIRMED");

        // the CSM's settlement log and the ledger agree (INV-10 after the test as well)
        assertThat(csmSettlements(transfer.payment())).singleElement().satisfies(settlement -> {
            assertThat(settlement.debtorAgentBic()).isEqualTo(transfer.debtorAgentBic());
            assertThat(settlement.direction()).isEqualTo("INCOMING");
            assertThat(settlement.amountMinor()).isEqualTo(AMOUNT_MINOR);
        });

        assertThat(finalEvents(transfer.payment())).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("PaymentSettled");
            PaymentSettled settled = new ContractJson().read(event.value(), PaymentSettled.class);
            assertThat(settled.direction()).isEqualTo(Direction.INCOMING);
            assertThat(settled.creditor().accountId()).isEqualTo(payee.account());
        });
    }

    /**
     * To an IBAN of our bank code that no account has: our {@code RJCT AC01}; no pending credit was created (the
     * cancel after the answer leaves only a tombstone), nothing booked, nothing settled at the CSM; one
     * {@code PaymentRejected AC01}, incoming.
     */
    @Test
    @Tag("smoke")
    void E2E_I_02_anIncomingPaymentToAnUnknownAccountIsRejectedAc01() throws SQLException {
        RejectedCount rejectedCount = rejectedIncoming("AC01");

        IncomingTransfer transfer = received(TestData.unknownOwnIban(), AMOUNT, "ACCEPT");

        assertThat(finalState(transfer.payment(), Bounds.paymentFinal())).isEqualTo("REJECTED");
        assertThat(orchestratorPayment(transfer.payment()).reasonCode()).isEqualTo("AC01");
        // AnswerSent and the answer's transmission leave the gateway side by side: the CSM may log it a little later
        await().atMost(Bounds.transmissionOverdue()).untilAsserted(() ->
                assertThat(simulator().answers(transfer.txId())).singleElement().satisfies(answer -> {
                    assertThat(answer.status()).isEqualTo("RJCT");
                    assertThat(answer.reason()).isEqualTo("AC01");
                }));
        assertThat(simulator().sentEnvelopes(transfer.txId(), PACS_002)).as("nothing after our RJCT").isEmpty();

        Response credit = pendingCredit(transfer.payment());
        assertThat(credit.status() == 404 || credit.body().path("tombstone").asBoolean(false))
                .as("no pending credit, at most the cancel's tombstone: %s", credit.body()).isTrue();
        assertThat(csmSettlements(transfer.payment())).isEmpty();
        assertThat(csmSettlementPostings(transfer.payment())).isEmpty();
        // the payee's account is unknown: a business refusal (20 §2)
        rejectedCount.assertTaggedOnce("BUSINESS");

        List<TopicRecorder.Recorded> events = finalEvents(transfer.payment());
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("PaymentRejected");
            PaymentRejected rejected = new ContractJson().read(event.value(), PaymentRejected.class);
            assertThat(rejected.direction()).isEqualTo(Direction.INCOMING);
            assertThat(rejected.reasonCode().code()).isEqualTo("AC01");
        });
    }

    /** The payment's final events, once one has arrived. */
    private static List<TopicRecorder.Recorded> finalEvents(UUID payment) {
        await().atMost(Bounds.eventDelivery())
                .until(() -> !topics().records(TopicRecorder.PAYMENTS_EVENTS, payment.toString()).isEmpty());
        return topics().records(TopicRecorder.PAYMENTS_EVENTS, payment.toString());
    }
}
