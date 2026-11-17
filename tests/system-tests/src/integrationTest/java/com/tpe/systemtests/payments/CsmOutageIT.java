package com.tpe.systemtests.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.iso20022model.Pacs002Builder.Status;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.systemtests.harness.Bounds;
import com.tpe.systemtests.harness.LocalDatabases;
import com.tpe.systemtests.harness.PaymentSystemTest;
import com.tpe.systemtests.harness.StatusReports;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Outages of csm-gateway and of the CSM around {@code sendBy}, and a late {@code pacs.002} that contradicts the first
 * answer (specs/20-testing-strategy.md §4, ADR-004, ADR-011): no {@code pacs.008} leaves after its {@code sendBy}
 * (INV-5, after every test), and no money moves twice. Timings are the {@code systemtest} profile's (20 §2.1):
 * {@code sendBy} 3 s and the deadline 6 s after acceptance. The CSM transport's outage is shared by every test of the
 * JVM, so it is set {@code UP} again however a test ends; so is a gateway stopped or frozen. Both are shared beyond
 * the creditor IBANs that keep other tests apart (20 §2), so the class runs alone.
 */
@Isolated
@EnabledIfSystemProperty(named = "tpe.local-stack", matches = "true",
        disabledReason = "needs the compose stack; run with -Dtpe.local-stack=true")
class CsmOutageIT extends PaymentSystemTest {

    private static final long FUNDED_MINOR = 50_000;
    private static final String PACS_002 = "pacs.002.001.10";
    private static final String PACS_008 = "pacs.008.001.08";
    private static final String INTO_INVESTIGATION = "SENT_TO_CSM|INVESTIGATING|DEADLINE";
    private static final String CONTRADICTION = "CONTRADICTING_CSM_OUTCOME";
    /** How far past {@code sendBy} the frozen gateway is thawed: its clock reads {@code sendBy} as passed. */
    private static final Duration PAST_SEND_BY = Duration.ofMillis(250);

    /**
     * The gateway is down from before the submit until just past {@code sendBy}, well before the deadline: it reads
     * the {@code SendCreditTransfer} late, sends nothing and answers {@code CreditTransferNotSent(AB03)}, which rejects
     * the payment from {@code SENT_TO_CSM}, before any investigation. A cold start takes longer than the 3 s between
     * {@code sendBy} and the deadline, so the gateway is frozen rather than stopped (20 §4).
     */
    @Test
    void E2E_O_12_aGatewayDownPastSendByRejectsAb03FromSentToCsm() throws SQLException {
        Party payer = customer(FUNDED_MINOR);
        Payee payee = peerPayee("ACCEPT");
        String reference = verifiedPeer(payer, payee);
        RejectedCount rejected = rejectedOutgoing("AB03");

        UUID payment;
        csmGateway().freeze();
        try {
            payment = paidToPeer(payer, payee, "E2E-O-12", reference);
            Instant thaw = orchestratorPayment(payment).sendBy().plus(PAST_SEND_BY);
            await().atMost(Bounds.paymentFinal()).pollInterval(Duration.ofMillis(50))
                    .until(() -> Instant.now().isAfter(thaw));
        } finally {
            csmGateway().thaw();
        }

        assertRejected(payer, payment, "AB03", Bounds.paymentFinal());
        assertThat(transitions(payment)).doesNotContain(INTO_INVESTIGATION).last()
                .isEqualTo("SENT_TO_CSM|REJECTED|CSM_NOT_SENT");
        rejected.assertTaggedOnce("TECHNICAL");
        assertNotSent(payment);
        assertReleasedAndNeverSettled(payer, payment);
    }

    /**
     * The CSM's transport is down ({@code DOWN}: {@code 503}; {@code RESET}: no answer) from the submit until the
     * payment is in investigation, past its deadline: the gateway tries the {@code pacs.008} until {@code sendBy} and
     * then no more; once the CSM is up, a {@code pacs.028} is answered {@code UNKNOWN_TX}, which after the deadline is
     * {@code RJCT AB05}; the hold is released.
     */
    @ParameterizedTest(name = "E2E-O-13 {0}")
    @ValueSource(strings = {"DOWN", "RESET"})
    void E2E_O_13_aCsmUnreachablePastSendByIsUnknownToItAndRejectedAb05(String outage) throws SQLException {
        Party payer = customer(FUNDED_MINOR);
        Payee payee = peerPayee("ACCEPT");
        String reference = verifiedPeer(payer, payee);
        RejectedCount rejected = rejectedOutgoing("AB05");

        UUID payment;
        simulator().outage(outage);
        try {
            payment = paidToPeer(payer, payee, "E2E-O-13 " + outage, reference);
            awaitInvestigating(payment);
        } finally {
            simulator().outage("UP");
        }

        assertRejected(payer, payment, "AB05", Bounds.paymentFinalAfterInvestigation());
        rejected.assertTaggedOnce("EXTERNAL");
        assertThat(transitions(payment)).contains(INTO_INVESTIGATION).last()
                .isEqualTo("INVESTIGATING|REJECTED|CSM_REJECTED");
        assertThat(csmReceived(payment, PACS_008)).as("no pacs.008 reached the CSM").isEmpty();
        assertThat(csmReceived(payment, "pacs.028.001.03")).isNotEmpty();
        assertThat(simulator().sentEnvelopes(orchestratorPayment(payment).txId(), PACS_002))
                .as("the CSM answered the investigation UNKNOWN_TX").anyMatch(report -> report.contains("UNKNOWN_TX"));
        assertReleasedAndNeverSettled(payer, payment);
    }

    /**
     * The CSM rejects ({@code REJECT:AC04}), then a {@code pacs.002 ACSC} of the same transaction arrives: the gateway
     * keeps the first, the orchestrator opens a {@code CONTRADICTING_CSM_OUTCOME} case, and nothing moves: the payment
     * stays {@code RJCT}, the hold released, nothing posted or settled.
     */
    @Test
    void E2E_O_14_aLateSettlementAfterARejectionKeepsTheRejection() throws SQLException {
        expectCase(CONTRADICTION);
        Party payer = customer(FUNDED_MINOR);
        RejectedCount rejected = rejectedOutgoing("AC04");
        UUID payment = paidToPeer(payer, peerPayee("REJECT:AC04"), "E2E-O-14");
        assertRejected(payer, payment, "AC04", Bounds.paymentFinal());
        rejected.assertTaggedOnce("EXTERNAL");
        assertReleasedAndNeverSettled(payer, payment);

        contradict(payment, Status.SETTLED);

        assertThat(finalStatus(payer, payment).get("reasonCode").asString()).isEqualTo("AC04");
        assertReleasedAndNeverSettled(payer, payment);
    }

    /**
     * E2E-O-14, the variant: the CSM settles ({@code ACCEPT}), then a {@code pacs.002 RJCT} arrives: the settlement is
     * kept, a {@code CONTRADICTING_CSM_OUTCOME} case is opened, the payer debited once and nothing released.
     */
    @Test
    void E2E_O_14_aLateRejectionAfterASettlementKeepsTheSettlement() throws SQLException {
        expectCase(CONTRADICTION);
        Party payer = customer(FUNDED_MINOR);
        UUID payment = paidToPeer(payer, peerPayee("ACCEPT"), "E2E-O-14 variant");
        assertThat(finalStatus(payer, payment).get("status").asString()).isEqualTo("ACSC");
        assertSettledOnce(payer, payment);

        contradict(payment, Status.rejected(ReasonCode.AC04));

        assertThat(finalStatus(payer, payment).get("status").asString()).isEqualTo("ACSC");
        assertSettledOnce(payer, payment);
    }

    /**
     * The gateway is down from before the submit until after the deadline: the payment goes into investigation, and
     * the gateway, once started, reads the {@code SendCreditTransfer} past its {@code sendBy} and the status request:
     * nothing was sent, {@code CreditTransferNotSent(AB03)}, no {@code pacs.028}. The gateway is started once the
     * payment is in investigation, past the deadline, and answers within the investigation's budget.
     */
    @Test
    void E2E_O_21_aGatewayDownPastTheDeadlineRejectsAb03FromInvestigation() throws SQLException {
        Party payer = customer(FUNDED_MINOR);
        Payee payee = peerPayee("ACCEPT");
        String reference = verifiedPeer(payer, payee);
        RejectedCount rejected = rejectedOutgoing("AB03");

        UUID payment;
        csmGateway().stop();
        try {
            payment = paidToPeer(payer, payee, "E2E-O-21", reference);
            awaitInvestigating(payment);
        } finally {
            csmGateway().start();
        }

        assertRejected(payer, payment, "AB03", Bounds.paymentFinal());
        assertThat(transitions(payment)).contains(INTO_INVESTIGATION).last()
                .isEqualTo("INVESTIGATING|REJECTED|CSM_NOT_SENT");
        rejected.assertTaggedOnce("TECHNICAL");
        assertNotSent(payment);
        assertReleasedAndNeverSettled(payer, payment);
    }

    /**
     * A {@code pacs.002} with {@code status}, contradicting the CSM's answer to the payment, sent to the gateway as the
     * CSM would; then the case it opens, and the gateway's note that it kept the first answer.
     */
    private static void contradict(UUID payment, Status status) throws SQLException {
        String txId = orchestratorPayment(payment).txId();
        String first = simulator().sentEnvelopes(txId, PACS_002).getLast();

        assertThat(simulator().sendRaw(StatusReports.contradicting(first, status))).isEqualTo(202);

        await().atMost(Bounds.csmEventHandled()).pollInterval(Duration.ofMillis(100))
                .until(() -> openCase(payment, CONTRADICTION) != null);
        assertThat(gatewayNotes(txId)).contains("contradicting status, first kept");
    }

    /** Until the payment is past its deadline and in investigation. */
    private static void awaitInvestigating(UUID payment) {
        await().atMost(Bounds.paymentFinal()).pollInterval(Duration.ofMillis(100))
                .until(() -> "INVESTIGATING".equals(orchestratorPayment(payment).state()));
    }

    private void assertRejected(Party payer, UUID payment, String reason, Duration bound) {
        JsonNode rejected = finalStatus(payer, payment, bound);
        assertThat(rejected.get("status").asString()).as("%s", rejected).isEqualTo("RJCT");
        assertThat(rejected.get("reasonCode").asString()).isEqualTo(reason);
    }

    /** Nothing reached the CSM; the gateway recorded the transfer {@code NOT_SENT AB03}. */
    private static void assertNotSent(UUID payment) throws SQLException {
        String txId = orchestratorPayment(payment).txId();
        assertThat(simulator().received(txId)).as("the CSM received nothing").isEmpty();
        assertThat(gatewaySendStatus(txId)).isEqualTo("NOT_SENT AB03");
    }

    private void assertReleasedAndNeverSettled(Party payer, UUID payment) throws SQLException {
        await().atMost(Bounds.paymentFinal()).untilAsserted(() ->
                assertThat(hold(payment).body().get("status").asString()).isEqualTo("RELEASED"));
        assertThat(postings(payer.account(), payment)).isEmpty();
        assertThat(available(payer)).isEqualTo(FUNDED_MINOR);
        assertThat(csmSettlements(payment)).isEmpty();
    }

    private void assertSettledOnce(Party payer, UUID payment) throws SQLException {
        assertThat(postings(payer.account(), payment)).containsExactly("PAYMENT_OUT DR " + PEER_PAYMENT_MINOR);
        assertThat(available(payer)).isEqualTo(FUNDED_MINOR - PEER_PAYMENT_MINOR);
        assertThat(csmSettlements(payment)).hasSize(1);
    }

    /** csm-gateway's {@code send_status} and {@code not_sent_reason} of the transaction; {@code null} if unknown. */
    private static String gatewaySendStatus(String txId) throws SQLException {
        try (var gateway = LocalDatabases.csm(); var statement = gateway.prepareStatement(
                "SELECT send_status || coalesce(' ' || not_sent_reason, '') FROM outgoing_transfers WHERE tx_id = ?")) {
            statement.setString(1, txId);
            try (var rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
