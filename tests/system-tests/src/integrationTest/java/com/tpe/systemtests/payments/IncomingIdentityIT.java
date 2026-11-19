package com.tpe.systemtests.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import com.tpe.invariantchecker.CsmInvariants.Answer;
import com.tpe.invariantchecker.CsmInvariants.Settlement;
import com.tpe.systemtests.harness.Bounds;
import com.tpe.systemtests.harness.PaymentSystemTest;
import com.tpe.systemtests.harness.TestData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * What one incoming transaction is, end to end (specs/20-testing-strategy.md §4; UC-06 1b, ADR-010): a
 * {@code pacs.008} the CSM delivers again is the same transaction, answered once; the same {@code TxId} from two debtor
 * agents is two. The per-test invariants (INV-4, -6, -7, -10, -11 for the incoming side, beside the ledger's) run after
 * each scenario.
 */
@EnabledIfSystemProperty(named = "tpe.local-stack", matches = "true",
        disabledReason = "needs the compose stack; run with -Dtpe.local-stack=true")
class IncomingIdentityIT extends PaymentSystemTest {

    private static final String AMOUNT = "12.34";
    private static final long AMOUNT_MINOR = 1_234;
    private static final String PACS_008 = "pacs.008.001.08";
    private static final String PEER_BIC = "PEERDEFFXXX";
    /** A debtor agent the bank directory does not know: the gateway takes any BIC as the transaction's (ADR-010). */
    private static final String OTHER_BIC = "OTHRDEFFXXX";

    /**
     * The simulator's own {@code pacs.008}, sent again through its raw endpoint: once right after the gateway took the
     * first, before the orchestrator's verdict (which is a round trip through Kafka, risk-service and the ledger away),
     * and once after our {@code ACCP} reached the CSM, within the answer's transmit window (15 §6.5). The early one is
     * dropped, the late one gets the same answer again, same {@code MsgId}; one payment, one credit.
     */
    @Test
    void E2E_I_10_aDuplicatePacs008IsDroppedBeforeOurAnswerAndAnsweredAgainAfterIt() throws SQLException {
        Party payee = customer(0);
        String txId = simulator().incomingPayment(payee.iban(), AMOUNT, TestData.newCustomer().name(),
                TestData.peerIban(), "ACCEPT");
        String pacs008 = simulator().sentEnvelopes(txId, PACS_008).getFirst();

        assertThat(simulator().sendRaw(pacs008)).as("the early duplicate").isEqualTo(202);
        IncomingTransfer transfer = awaitIncoming(txId, PEER_BIC);
        await().atMost(Bounds.answerOverdue()).untilAsserted(() ->
                assertThat(simulator().answers(txId)).extracting(Answer::status).containsExactly("ACCP"));
        assertThat(gatewayNotes(txId)).as("the early duplicate came before our answer")
                .containsExactly("duplicate pacs.008 dropped");

        assertThat(simulator().sendRaw(pacs008)).as("the late duplicate").isEqualTo(202);
        await().atMost(Bounds.transmissionOverdue()).untilAsserted(() ->
                assertThat(simulator().answers(txId)).hasSize(2));
        List<Answer> answers = simulator().answers(txId);
        assertThat(answers.get(1)).as("the same pacs.002, same MsgId").isEqualTo(answers.get(0));
        assertThat(gatewayNotes(txId)).containsExactly("duplicate pacs.008 dropped",
                "duplicate pacs.008, answer re-sent");

        assertThat(finalState(transfer.payment(), Bounds.paymentFinal())).isEqualTo("SETTLED");
        assertThat(incomingPayments(txId, null)).as("one payment").singleElement()
                .isEqualTo(transfer);
        await().atMost(Bounds.eventDelivery()).untilAsserted(() ->
                assertThat(postings(payee.account(), transfer.payment())).containsExactly("PAYMENT_IN CR "
                        + AMOUNT_MINOR));
        assertThat(booked(payee)).as("one credit").isEqualTo(AMOUNT_MINOR);
        assertThat(csmSettlementPostings(transfer.payment())).containsExactly("DR " + AMOUNT_MINOR);
    }

    /**
     * Two {@code pacs.008} with the same {@code TxId}, from two debtor agents, to the same payee: two transactions
     * (ADR-010), each its own payment, answer, settlement and credit. Sent through the simulator's structured endpoint
     * with an explicit {@code txId} and {@code debtorAgentBic} (17 §3.5), not as raw XML: the CSM plays (answers,
     * settles, confirms) only a payment it built itself.
     */
    @Test
    void E2E_I_12_theSameTxIdFromTwoDebtorAgentsIsTwoIndependentPayments() throws SQLException {
        Party payee = customer(0);
        String txId = TestData.newTxId();
        Instant since = Instant.now().minusSeconds(1);

        IncomingTransfer fromPeer = received(payee.iban(), AMOUNT, "ACCEPT", txId, PEER_BIC);
        IncomingTransfer fromOther = received(payee.iban(), AMOUNT, "ACCEPT", txId, OTHER_BIC);

        assertThat(fromPeer.payment()).isNotEqualTo(fromOther.payment());
        assertThat(List.of(fromPeer.debtorAgentBic(), fromOther.debtorAgentBic())).containsExactly(PEER_BIC,
                OTHER_BIC);
        for (IncomingTransfer transfer : List.of(fromPeer, fromOther)) {
            assertThat(finalState(transfer.payment(), Bounds.paymentFinal())).as("%s", transfer)
                    .isEqualTo("SETTLED");
            await().atMost(Bounds.eventDelivery()).untilAsserted(() ->
                    assertThat(postings(payee.account(), transfer.payment())).as("%s", transfer)
                            .containsExactly("PAYMENT_IN CR " + AMOUNT_MINOR));
            assertThat(csmSettlementPostings(transfer.payment())).as("%s", transfer)
                    .containsExactly("DR " + AMOUNT_MINOR);
        }
        assertThat(incomingPayments(txId, null)).containsExactly(fromPeer, fromOther);
        assertThat(simulator().answers(txId)).extracting(Answer::debtorAgentBic, Answer::status)
                .containsExactlyInAnyOrder(tuple(PEER_BIC, "ACCP"), tuple(OTHER_BIC, "ACCP"));
        assertThat(simulator().settlements(since)).filteredOn(settlement -> settlement.txId().equals(txId))
                .extracting(Settlement::debtorAgentBic, Settlement::direction, Settlement::amountMinor)
                .containsExactlyInAnyOrder(tuple(PEER_BIC, "INCOMING", AMOUNT_MINOR),
                        tuple(OTHER_BIC, "INCOMING", AMOUNT_MINOR));
        assertThat(booked(payee)).as("two credits").isEqualTo(2 * AMOUNT_MINOR);
    }
}
