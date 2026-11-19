package com.tpe.systemtests.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.contracts.BlockReason;
import com.tpe.contracts.ContractJson;
import com.tpe.contracts.Direction;
import com.tpe.contracts.PaymentRejected;
import com.tpe.invariantchecker.CsmInvariants.Answer;
import com.tpe.systemtests.harness.Bounds;
import com.tpe.systemtests.harness.IncomingCreditTransfers;
import com.tpe.systemtests.harness.LocalDatabases;
import com.tpe.systemtests.harness.PaymentSystemTest;
import com.tpe.systemtests.harness.Response;
import com.tpe.systemtests.harness.TestData;
import com.tpe.systemtests.harness.TopicRecorder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * The refusals of an incoming payment, at or before our answer, end to end (specs/20-testing-strategy.md §4; UC-06 1a,
 * 3a, 4a): the payee's account blocked or closed, refused by the ledger's pending credit; the mule rule of risk; and a
 * {@code pacs.008} csm-gateway cannot take, answered by the gateway itself without a payment. The per-test invariants
 * (INV-4, -6, -7, -10, -11 for the incoming side, beside the ledger's) run after each scenario; the webhook outcomes
 * (INV-13) come with the notifications track.
 */
@EnabledIfSystemProperty(named = "tpe.local-stack", matches = "true",
        disabledReason = "needs the compose stack; run with -Dtpe.local-stack=true")
class IncomingRefusalIT extends PaymentSystemTest {

    private static final String AMOUNT = "12.34";
    private static final String PACS_002 = "pacs.002.001.10";
    private static final String PEER_BIC = "PEERDEFFXXX";
    /** The {@code cause} of a refusal by an account state or a rule (20 §2). */
    private static final String BUSINESS = "BUSINESS";

    @Test
    void E2E_I_03_anIncomingPaymentToABlockedAccountIsRejectedAc06() throws SQLException {
        Party payee = customer(0);
        assertThat(ledger().block(OPERATOR, payee.account(), BlockReason.OPERATOR).status()).isEqualTo(200);
        RejectedCount rejected = rejectedIncoming("AC06");

        IncomingTransfer transfer = received(payee.iban(), AMOUNT, "ACCEPT");

        assertRejected(transfer, "AC06");
        rejected.assertTaggedOnce(BUSINESS);
        assertThat(booked(payee)).isZero();
    }

    @Test
    void E2E_I_04_anIncomingPaymentToAClosedAccountIsRejectedAc04() throws SQLException {
        Party payee = customer(0);
        assertThat(ledger().close(OPERATOR, payee.account()).status()).isEqualTo(200);
        RejectedCount rejected = rejectedIncoming("AC04");

        IncomingTransfer transfer = received(payee.iban(), AMOUNT, "ACCEPT");

        assertRejected(transfer, "AC04");
        rejected.assertTaggedOnce(BUSINESS);
        assertThat(booked(payee)).isZero();
    }

    /**
     * {@code MULE_NEW_ACCOUNT} (14 §6.1): an account opened today that has received ten incoming payments within 24 h
     * is refused the eleventh, the limit including the payment assessed. The ten are settled and credited; only
     * {@code ALLOW}ed assessments count.
     */
    @Test
    void E2E_I_05_theEleventhIncomingPaymentToANewAccountWithin24hIsRejectedFr01() throws SQLException {
        Party payee = customer(0);
        for (int i = 0; i < 10; i++) {
            IncomingTransfer earlier = received(payee.iban(), "1.00", "ACCEPT");
            assertThat(finalState(earlier.payment(), Bounds.paymentFinal())).as("payment %d", i + 1)
                    .isEqualTo("SETTLED");
        }
        RejectedCount rejected = rejectedIncoming("FR01");

        IncomingTransfer transfer = received(payee.iban(), "1.00", "ACCEPT");

        assertRejected(transfer, "FR01");
        assertThat(ruleHits(transfer.payment())).as("FR01 by the mule rule, not another one")
                .containsExactly("MULE_NEW_ACCOUNT");
        rejected.assertTaggedOnce(BUSINESS);
        await().atMost(Bounds.eventDelivery()).untilAsserted(() -> assertThat(booked(payee)).isEqualTo(1_000));
    }

    /** The rules risk-service's assessment of {@code payment} hit ({@code assessments.rule_hits}, 14 §5). */
    private static List<String> ruleHits(UUID payment) throws SQLException {
        try (var risk = LocalDatabases.risk(); var statement = risk.prepareStatement("""
                SELECT hit ->> 'ruleId' FROM assessments, jsonb_array_elements(rule_hits) AS hit
                 WHERE payment_id = ? ORDER BY 1""")) {
            statement.setObject(1, payment);
            var hits = new ArrayList<String>();
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    hits.add(rs.getString(1));
                }
            }
            return hits;
        }
    }

    /**
     * Schema-invalid, its identity readable: csm-gateway answers {@code RJCT FF01} itself (15 §6.2 step 2) and
     * publishes nothing, so the orchestrator never has a payment of it.
     */
    @Test
    void E2E_I_09_anInvalidPacs008IsRejectedFf01WithoutAPayment() throws SQLException {
        Party payee = customer(0);
        String txId = TestData.newTxId();

        assertThat(simulator().sendRaw(IncomingCreditTransfers.schemaInvalid(txId, payee.iban()))).isEqualTo(202);

        assertAnsweredFf01WithoutAPayment(txId);
        assertThat(booked(payee)).isZero();
    }

    /**
     * The variant with a DOCTYPE whose external entity is a file on this machine, the gateway's: refused
     * {@code RJCT FF01} with the entity never resolved, so the file's content is nowhere (GW-AC-14, ISO-AC-02).
     */
    @Test
    void E2E_I_09_aPacs008WithADoctypeIsRejectedFf01WithoutResolvingTheEntity(@TempDir Path dir) throws Exception {
        Party payee = customer(0);
        String txId = TestData.newTxId();
        String secret = "SYSTEST-LEAKED-" + UUID.randomUUID();
        Path file = Files.writeString(dir.resolve("secret.txt"), secret);

        assertThat(simulator().sendRaw(IncomingCreditTransfers.withDoctype(txId, payee.iban(), file.toUri())))
                .isEqualTo(202);

        assertAnsweredFf01WithoutAPayment(txId);
        assertThat(gatewayJournalContains(secret)).as("the entity's content in the gateway's journal").isFalse();
        assertThat(simulator().received(txId)).isNotEmpty();
        assertThat(simulator().receivedLog(txId)).as("the entity's content in what reached the CSM")
                .doesNotContain(secret);
    }

    /**
     * Our {@code RJCT reason}, the payment {@code REJECTED} with it, nothing after it from the CSM; no pending credit
     * left active (the ledger refused it, or the cancel left it {@code CANCELLED} or a tombstone), nothing settled; one
     * {@code PaymentRejected}, incoming.
     */
    private static void assertRejected(IncomingTransfer transfer, String reason) throws SQLException {
        assertThat(finalState(transfer.payment(), Bounds.paymentFinal())).isEqualTo("REJECTED");
        assertThat(orchestratorPayment(transfer.payment()).reasonCode()).isEqualTo(reason);
        // AnswerSent and the answer's transmission leave the gateway side by side: the CSM may log it a little later
        await().atMost(Bounds.transmissionOverdue()).untilAsserted(() ->
                assertThat(simulator().answers(transfer.txId())).singleElement().satisfies(answer -> {
                    assertThat(answer.status()).isEqualTo("RJCT");
                    assertThat(answer.reason()).isEqualTo(reason);
                }));
        assertThat(simulator().sentEnvelopes(transfer.txId(), PACS_002)).as("nothing after our RJCT").isEmpty();

        Response credit = pendingCredit(transfer.payment());
        assertThat(credit.status() == 404 || credit.body().path("tombstone").asBoolean(false)
                || "CANCELLED".equals(credit.body().path("status").asString(null)))
                .as("no pending credit left active: %s", credit.body()).isTrue();
        assertThat(csmSettlements(transfer.payment())).isEmpty();
        assertThat(csmSettlementPostings(transfer.payment())).isEmpty();

        await().atMost(Bounds.eventDelivery()).until(() ->
                !topics().records(TopicRecorder.PAYMENTS_EVENTS, transfer.payment().toString()).isEmpty());
        assertThat(topics().records(TopicRecorder.PAYMENTS_EVENTS, transfer.payment().toString())).singleElement()
                .satisfies(event -> {
                    assertThat(event.type()).isEqualTo("PaymentRejected");
                    PaymentRejected rejected = new ContractJson().read(event.value(), PaymentRejected.class);
                    assertThat(rejected.direction()).isEqualTo(Direction.INCOMING);
                    assertThat(rejected.reasonCode().code()).isEqualTo(reason);
                });
    }

    /**
     * csm-gateway's own {@code RJCT FF01} reached the CSM and was delivered; the orchestrator has no payment of the
     * transaction, also a while after it (an event would have been handled by then). The per-test sweep covers the
     * test's payments, so it has nothing of such a transaction: what INV-6 and INV-11 ask of it is asserted here.
     */
    private static void assertAnsweredFf01WithoutAPayment(String txId) throws SQLException {
        await().atMost(Bounds.transmissionOverdue()).untilAsserted(() ->
                assertThat(simulator().answers(txId)).singleElement().satisfies(answer -> {
                    assertThat(answer.debtorAgentBic()).isEqualTo(PEER_BIC);
                    assertThat(answer.status()).isEqualTo("RJCT");
                    assertThat(answer.reason()).isEqualTo("FF01");
                }));
        await().atMost(Bounds.transmissionOverdue()).untilAsserted(() ->
                assertThat(gatewayIncoming(PEER_BIC, txId)).isEqualTo("RJCT FF01 VALIDATION DELIVERED"));
        // held for a whole bound; atMost must exceed during, and twice the bound leaves the poll its room
        await().during(Bounds.csmEventHandled()).atMost(Bounds.csmEventHandled().multipliedBy(2))
                .until(() -> incomingPayments(txId, null).isEmpty());
        assertThat(simulator().answers(txId)).extracting(Answer::status).containsExactly("RJCT");
    }
}
