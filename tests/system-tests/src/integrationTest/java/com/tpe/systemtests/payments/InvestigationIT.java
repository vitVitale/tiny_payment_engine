package com.tpe.systemtests.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.invariantchecker.CsmInvariants.SimulatorMessage;
import com.tpe.systemtests.harness.Bounds;
import com.tpe.systemtests.harness.LocalDatabases;
import com.tpe.systemtests.harness.PaymentSystemTest;
import com.tpe.systemtests.harness.Response;
import com.tpe.systemtests.harness.ServiceJar;
import com.tpe.systemtests.harness.ServiceProfile;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;

/**
 * Every way the CSM can leave an outgoing payment without an answer, resolved end to end (specs/20-testing-strategy.md
 * §4, UC-05): by the CSM's own timeout, by the orchestrator's investigation ({@code pacs.028}), or by an operator once
 * the investigation gives up. Timings are the {@code systemtest} profile's (20 §2.1): the deadline 6 s after
 * acceptance, status requests after 500 ms then every 1 s, a budget of 10 s. The per-test invariants run after each
 * scenario; only E2E-O-11 expects its {@code UNRESOLVED_INVESTIGATION} case.
 */
@EnabledIfSystemProperty(named = "tpe.local-stack", matches = "true",
        disabledReason = "needs the compose stack; run with -Dtpe.local-stack=true")
class InvestigationIT extends PaymentSystemTest {

    private static final long FUNDED_MINOR = 50_000;
    private static final String PACS_028 = "pacs.028.001.03";
    private static final String INTO_INVESTIGATION = "SENT_TO_CSM|INVESTIGATING|DEADLINE";

    /**
     * The beneficiary never answers ({@code TIMEOUT}): at the scheme deadline the CSM rejects {@code AB05}, by its own
     * {@code pacs.002} or as the answer to the investigation that starts at the same deadline; the hold is released,
     * nothing is settled.
     */
    @Test
    void E2E_O_08_aBeneficiaryThatNeverAnswersIsRejectedAb05AndNothingSettles() throws SQLException {
        Party payer = customer(FUNDED_MINOR);
        RejectedCount rejected = rejectedOutgoing("AB05");
        UUID payment = paidToPeer(payer, peerPayee("TIMEOUT"), "E2E-O-08");

        assertRejected(payer, payment, "AB05");
        assertReleasedAndNeverSettled(payer, payment);
        // the beneficiary's timeout: the scheme's cause (20 §2)
        rejected.assertTaggedOnce("EXTERNAL");
    }

    /**
     * The CSM settles but sends no {@code pacs.002} ({@code SILENT}): at the deadline the payment goes into
     * investigation, a {@code pacs.028} is answered {@code ACSC}, and the payer is debited.
     */
    @Test
    void E2E_O_09_aSettlementWithoutAStatusReportIsFoundByTheInvestigation() throws SQLException {
        Party payer = customer(FUNDED_MINOR);
        UUID payment = paidToPeer(payer, peerPayee("SILENT"), "E2E-O-09");

        JsonNode settled = finalStatus(payer, payment, Bounds.paymentFinalAfterInvestigation());

        assertThat(settled.get("status").asString()).as("%s", settled).isEqualTo("ACSC");
        assertThat(transitions(payment)).contains(INTO_INVESTIGATION).last()
                .isEqualTo("INVESTIGATING|SETTLED|HOLD_SETTLED");
        assertThat(received(payment, PACS_028)).isNotEmpty();
        assertThat(postings(payer.account(), payment)).containsExactly("PAYMENT_OUT DR " + PEER_PAYMENT_MINOR);
        assertThat(available(payer)).isEqualTo(FUNDED_MINOR - PEER_PAYMENT_MINOR);
    }

    /**
     * The CSM takes the transport and drops the message ({@code LOST}): the investigation's {@code pacs.028} is
     * answered {@code UNKNOWN_TX} after the deadline, which is {@code RJCT AB05}; the hold is released.
     */
    @Test
    void E2E_O_10_aLostTransferIsUnknownToTheCsmAndRejectedAb05() throws SQLException {
        Party payer = customer(FUNDED_MINOR);
        RejectedCount rejected = rejectedOutgoing("AB05");
        UUID payment = paidToPeer(payer, peerPayee("LOST"), "E2E-O-10");

        assertRejected(payer, payment, "AB05");
        rejected.assertTaggedOnce("EXTERNAL");
        assertThat(transitions(payment)).contains(INTO_INVESTIGATION).last()
                .isEqualTo("INVESTIGATING|REJECTED|CSM_REJECTED");
        assertThat(received(payment, PACS_028)).isNotEmpty();
        assertReleasedAndNeverSettled(payer, payment);
    }

    /**
     * The CSM settles and answers no investigation ({@code SILENT+INVESTIGATION_SILENT}): status requests are retried
     * until the budget runs out, then an {@code UNRESOLVED_INVESTIGATION} case; meanwhile the customer sees
     * {@code PDNG} and the hold stays. The operator confirms the settlement: {@code ACSC}, the payer debited.
     */
    @Test
    void E2E_O_11_anUnansweredInvestigationIsResolvedByTheOperator() throws SQLException {
        expectCase("UNRESOLVED_INVESTIGATION");
        Party payer = customer(FUNDED_MINOR);
        UUID payment = paidToPeer(payer, peerPayee("SILENT+INVESTIGATION_SILENT"), "E2E-O-11");

        UUID caseId = awaitUnresolvedCase(payer, payment);

        assertThat(resolve(caseId, Map.of("resolution", "CONFIRMED_SETTLED", "comment", "E2E-O-11")).status())
                .isEqualTo(200);
        assertThat(finalStatus(payer, payment).get("status").asString()).isEqualTo("ACSC");
        assertThat(postings(payer.account(), payment)).containsExactly("PAYMENT_OUT DR " + PEER_PAYMENT_MINOR);
    }

    /**
     * E2E-O-11, the variant: the CSM answers investigations again ({@code INVESTIGATION_SILENT} cleared), the operator
     * retries the investigation, and a new {@code pacs.028} reaches the CSM and is answered {@code ACSC}.
     */
    @Test
    void E2E_O_11_aRetriedInvestigationIsAnsweredOnceTheCsmAnswersAgain() throws SQLException {
        expectCase("UNRESOLVED_INVESTIGATION");
        Party payer = customer(FUNDED_MINOR);
        Payee payee = peerPayee("SILENT+INVESTIGATION_SILENT");
        UUID payment = paidToPeer(payer, payee, "E2E-O-11 retry");
        UUID caseId = awaitUnresolvedCase(payer, payment);
        int asked = received(payment, PACS_028).size();

        simulator().scenario(payee.iban(), "SILENT");
        assertThat(resolve(caseId, Map.of("resolution", "RETRY_INVESTIGATION")).status()).isEqualTo(200);

        assertThat(finalStatus(payer, payment, Bounds.statusRequestAnswered()).get("status").asString())
                .isEqualTo("ACSC");
        assertThat(received(payment, PACS_028)).as("a new pacs.028 reached the CSM").hasSizeGreaterThan(asked);
        assertThat(postings(payer.account(), payment)).containsExactly("PAYMENT_OUT DR " + PEER_PAYMENT_MINOR);
    }

    /**
     * The CSM settles but its {@code pacs.002} comes a second after the deadline
     * ({@code LATE_STATUS:<deadline + 1 s>}): the payment goes into investigation, whose first {@code pacs.028}, sent
     * at the deadline, the CSM answers {@code ACSC} (17 §3.2), so the investigation settles it; the late
     * {@code pacs.002} is a duplicate status at the gateway and publishes nothing. One settlement at the CSM, one in
     * the ledger, one {@code PaymentSettled} (INV-7, -8, -10 after the test).
     */
    @Test
    void E2E_O_20_aLateStatusReportAfterTheInvestigationSettlesOnce() throws SQLException {
        Duration deadline = ServiceProfile.systemtest(ServiceJar.ORCHESTRATOR)
                .duration("tpe.outgoing.deadline-offset").orElseThrow();
        Party payer = customer(FUNDED_MINOR);
        UUID payment = paidToPeer(payer, peerPayee("LATE_STATUS:" + deadline.plusSeconds(1).toMillis()),
                "E2E-O-20");

        assertThat(finalStatus(payer, payment, Bounds.paymentFinalAfterInvestigation()).get("status").asString())
                .isEqualTo("ACSC");
        assertThat(transitions(payment)).contains(INTO_INVESTIGATION);
        String txId = orchestratorPayment(payment).txId();
        assertThat(gatewayFinalSource(txId)).as("settled by the investigation's answer").isEqualTo("INVESTIGATION");
        // the late report, a second after the deadline, reached the gateway and changed nothing
        await().atMost(Bounds.paymentFinalAfterInvestigation())
                .until(() -> gatewayNotes(txId).contains("duplicate status"));
        assertThat(csmSettlements(payment)).hasSize(1);
        assertThat(postings(payer.account(), payment)).containsExactly("PAYMENT_OUT DR " + PEER_PAYMENT_MINOR);
    }

    /**
     * Waits for the payment's investigation to run out, asserting what holds meanwhile: while it is
     * {@code INVESTIGATING} the customer sees {@code PDNG}; once the case is open the hold is still {@code ACTIVE}
     * and several status requests were asked. The case's id.
     */
    private UUID awaitUnresolvedCase(Party payer, UUID payment) throws SQLException {
        await().atMost(Bounds.paymentFinal()).until(() -> "INVESTIGATING".equals(
                orchestratorPayment(payment).state()));
        JsonNode investigating = status(payer, payment);
        assertThat(investigating.get("status").asString()).as("%s", investigating).isEqualTo("PDNG");
        assertThat(investigating.get("internalState").asString()).isEqualTo("INVESTIGATING");

        await().atMost(Bounds.investigationUnresolved()).pollInterval(Duration.ofMillis(250))
                .until(() -> openCase(payment) != null);
        assertThat(status(payer, payment).get("status").asString()).isEqualTo("PDNG");
        assertThat(hold(payment).body().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(received(payment, PACS_028)).as("status requests retried").hasSizeGreaterThan(1);
        return openCase(payment);
    }

    private static Response resolve(UUID caseId, Map<String, String> body) {
        return caseApi().post(OPERATOR, "/v1/exception-cases/" + caseId + "/resolve", body);
    }

    /** The payment's {@code OPEN} {@code UNRESOLVED_INVESTIGATION} case; {@code null} if none. */
    private static UUID openCase(UUID payment) throws SQLException {
        try (var orchestrator = LocalDatabases.payments(); var statement = orchestrator.prepareStatement("""
                SELECT case_id FROM exception_cases
                 WHERE payment_id = ? AND type = 'UNRESOLVED_INVESTIGATION' AND status = 'OPEN'""")) {
            statement.setObject(1, payment);
            try (var rs = statement.executeQuery()) {
                return rs.next() ? rs.getObject(1, UUID.class) : null;
            }
        }
    }

    private void assertRejected(Party payer, UUID payment, String reason) {
        JsonNode rejected = finalStatus(payer, payment, Bounds.paymentFinalAfterInvestigation());
        assertThat(rejected.get("status").asString()).as("%s", rejected).isEqualTo("RJCT");
        assertThat(rejected.get("reasonCode").asString()).isEqualTo(reason);
    }

    private void assertReleasedAndNeverSettled(Party payer, UUID payment) throws SQLException {
        await().atMost(Bounds.paymentFinal()).untilAsserted(() ->
                assertThat(hold(payment).body().get("status").asString()).isEqualTo("RELEASED"));
        assertThat(postings(payer.account(), payment)).isEmpty();
        assertThat(available(payer)).isEqualTo(FUNDED_MINOR);
        String txId = orchestratorPayment(payment).txId();
        assertThat(csmSettlements(payment)).isEmpty();
    }

    /** csm-gateway's {@code final_source} of the transaction: which answer settled or rejected it (15 §5). */
    private static String gatewayFinalSource(String txId) throws SQLException {
        try (var gateway = LocalDatabases.csm(); var statement = gateway.prepareStatement(
                "SELECT final_source FROM outgoing_transfers WHERE tx_id = ?")) {
            statement.setString(1, txId);
            try (var rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** The notes csm-gateway's journal has for the transaction, e.g. {@code duplicate status}. */
    private static List<String> gatewayNotes(String txId) throws SQLException {
        try (var gateway = LocalDatabases.csm(); var statement = gateway.prepareStatement(
                "SELECT note FROM message_journal WHERE tx_id = ? AND note IS NOT NULL ORDER BY id")) {
            statement.setString(1, txId);
            var notes = new ArrayList<String>();
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    notes.add(rs.getString(1));
                }
            }
            return notes;
        }
    }

    /** The messages of {@code messageType} the CSM received for the payment's transaction. */
    private static List<SimulatorMessage> received(UUID payment, String messageType) throws SQLException {
        return simulator().received(orchestratorPayment(payment).txId()).stream()
                .filter(message -> message.kind().equals("RECEIVED") && message.messageType().equals(messageType))
                .toList();
    }

}
