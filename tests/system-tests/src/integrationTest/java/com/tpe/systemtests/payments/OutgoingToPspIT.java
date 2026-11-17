package com.tpe.systemtests.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.contracts.ContractJson;
import com.tpe.contracts.PaymentRejected;
import com.tpe.contracts.PaymentSettled;
import com.tpe.invariantchecker.CsmInvariants.SimulatorMessage;
import com.tpe.systemtests.harness.Bounds;
import com.tpe.systemtests.harness.JsonHttp;
import com.tpe.systemtests.harness.LocalDatabases;
import com.tpe.systemtests.harness.PaymentSystemTest;
import com.tpe.systemtests.harness.TopicRecorder;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;

/**
 * Payments to another PSP end to end (specs/20-testing-strategy.md §4; UC-01, UC-02 remote, UC-04, UC-07): payee
 * verification by the peer bank's responder, the transfer through csm-gateway to the CSM (csm-simulator) and its
 * outcome back, as the customer channel sees it, the ledger books it and the CSM logs it. The per-test invariants
 * (INV-3, -5, -7, -8, -9, -10, -11 beside the ledger's) run after each scenario; the webhook outcomes (INV-13) come
 * with the notifications track.
 */
@EnabledIfSystemProperty(named = "tpe.local-stack", matches = "true",
        disabledReason = "needs the compose stack; run with -Dtpe.local-stack=true")
class OutgoingToPspIT extends PaymentSystemTest {

    private static final long FUNDED_MINOR = 50_000;
    private static final long AMOUNT_MINOR = 12_345;
    private static final String OUR_BIC = "TPEBDEFFXXX";
    private static final String PACS_008 = "pacs.008.001.08";

    /**
     * The CSM settles ({@code ACCEPT}): {@code ACSC} as JSON and {@code pain.002}; the payer debited by a
     * {@code PAYMENT_OUT} entry whose credit is the CSM settlement account; one {@code pacs.008} reached the CSM, which
     * logged the settlement of the payment's amount; one {@code PaymentSettled}, not on-us.
     */
    @Test
    @Tag("smoke")
    void E2E_O_01_aPaymentToAnotherPspIsSettledByTheCsm() throws SQLException {
        Party payer = customer(FUNDED_MINOR);
        Payee payee = peerPayee("ACCEPT");

        UUID payment = paid(payer, payee, "E2E-O-01");

        JsonNode settled = finalStatus(payer, payment);
        assertThat(settled.get("status").asString()).as("%s", settled).isEqualTo("ACSC");
        assertThat(settled.get("internalState").asString()).isEqualTo("SETTLED");
        assertThat(pain002(payer, payment)).contains("<GrpSts>ACSC</GrpSts>", "<TxSts>ACSC</TxSts>",
                "<OrgnlEndToEndId>E2E-O-01</OrgnlEndToEndId>");

        // payer debited, the CSM settlement account credited (INV-8 checks the CSM_SETTLEMENT side after the test)
        assertThat(postings(payer.account(), payment)).containsExactly("PAYMENT_OUT DR " + AMOUNT_MINOR);
        assertThat(available(payer)).isEqualTo(FUNDED_MINOR - AMOUNT_MINOR);
        assertThat(booked(payer)).isEqualTo(FUNDED_MINOR - AMOUNT_MINOR);
        assertThat(hold(payment).body().get("status").asString()).isEqualTo("SETTLED");

        // one pacs.008 reached the CSM; its settlement log and the ledger agree (INV-10 after the test as well)
        String txId = txIdOf(payment);
        assertThat(simulator().received(txId)).filteredOn(message -> message.kind().equals("RECEIVED"))
                .extracting(SimulatorMessage::messageType).containsExactly(PACS_008);
        assertThat(simulator().settlements(acceptedAt(payment))).filteredOn(s -> s.txId().equals(txId))
                .singleElement().satisfies(settlement -> {
                    assertThat(settlement.debtorAgentBic()).isEqualTo(OUR_BIC);
                    assertThat(settlement.direction()).isEqualTo("OUTGOING");
                    assertThat(settlement.amountMinor()).isEqualTo(AMOUNT_MINOR);
                });

        List<TopicRecorder.Recorded> events = finalEvents(payment);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("PaymentSettled");
            assertThat(new ContractJson().read(event.value(), PaymentSettled.class).onUs()).isFalse();
        });
    }

    /**
     * The beneficiary rejects ({@code REJECT:AC04}): {@code RJCT AC04} as JSON and {@code pain.002}; the hold
     * released, the payer's balance whole, no {@code CSM_SETTLEMENT} posting and no settlement at the CSM; one
     * {@code PaymentRejected AC04}.
     */
    @Test
    @Tag("smoke")
    void E2E_O_07_aPaymentTheBeneficiaryRejectsIsRejectedAc04AndItsHoldReleased() throws SQLException {
        Party payer = customer(FUNDED_MINOR);
        Payee payee = peerPayee("REJECT:AC04");
        RejectedCount rejectedCount = rejectedOutgoing("AC04");

        UUID payment = paid(payer, payee, "E2E-O-07");

        JsonNode rejected = finalStatus(payer, payment);
        assertThat(rejected.get("status").asString()).as("%s", rejected).isEqualTo("RJCT");
        assertThat(rejected.get("reasonCode").asString()).isEqualTo("AC04");
        assertThat(rejected.get("internalState").asString()).isEqualTo("REJECTED");
        assertThat(pain002(payer, payment)).contains("<TxSts>RJCT</TxSts>", "<Cd>AC04</Cd>");
        // the beneficiary's refusal: the scheme's cause, not ours nor a business rule of ours (20 §2)
        rejectedCount.assertTaggedOnce("EXTERNAL");

        await().atMost(Bounds.paymentFinal()).untilAsserted(() ->
                assertThat(hold(payment).body().get("status").asString()).isEqualTo("RELEASED"));
        assertThat(postings(payer.account(), payment)).isEmpty();
        assertThat(available(payer)).isEqualTo(FUNDED_MINOR);
        String txId = txIdOf(payment);
        assertThat(simulator().received(txId)).filteredOn(message -> message.kind().equals("RECEIVED"))
                .extracting(SimulatorMessage::messageType).containsExactly(PACS_008);
        assertThat(simulator().settlements(acceptedAt(payment))).noneMatch(s -> s.txId().equals(txId));

        assertThat(finalEvents(payment)).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("PaymentRejected");
            assertThat(new ContractJson().read(event.value(), PaymentRejected.class).reasonCode().code())
                    .isEqualTo("AC04");
        });
    }

    /**
     * The payee verified, then {@code payer} pays it 123.45 EUR. vop-service cannot ask a peer bank's responder yet
     * (its {@code NoCounterpartyVop} until the VoP remote track, 13 §6.1 step 3), so the reference says
     * {@code NOT_POSSIBLE}, which the customer may pay on (UC-02 3a) and risk lets pass at this amount; with the remote
     * client the simulator's responder answers {@code MATCH} for the account {@link #peerPayee} seeded.
     */
    private UUID paid(Party payer, Payee payee, String endToEndId) {
        JsonNode verification = verified(payer, payee.iban(), payee.name(), "NOT_POSSIBLE");
        var accepted = initiate(payer.token(), Map.of(
                "endToEndId", endToEndId,
                "debtor", Map.of("name", payer.name(), "iban", payer.iban()),
                "creditor", Map.of("name", payee.name(), "iban", payee.iban()),
                "amount", Map.of("value", "123.45", "currency", "EUR"),
                "remittanceInformation", endToEndId + " to another PSP",
                "vopReference", verification.get("vopReference").asString()));
        assertThat(accepted.status()).as("%s", accepted.body()).isEqualTo(202);
        return UUID.fromString(accepted.body().get("paymentId").asString());
    }

    private String pain002(Party payer, UUID payment) {
        JsonHttp.Text pain002 = paymentApi().getText(payer.token(), "/v1/payments/" + payment + "/pain.002",
                "application/xml");
        assertThat(pain002.status()).isEqualTo(200);
        return pain002.body();
    }

    /** The payment's final events, once one has arrived. */
    private static List<TopicRecorder.Recorded> finalEvents(UUID payment) {
        await().atMost(Bounds.eventDelivery())
                .until(() -> !topics().records(TopicRecorder.PAYMENTS_EVENTS, payment.toString()).isEmpty());
        return topics().records(TopicRecorder.PAYMENTS_EVENTS, payment.toString());
    }

    private static String txIdOf(UUID payment) throws SQLException {
        return orchestratorRow(payment).txId();
    }

    /**
     * The orchestrator's acceptance of the payment, a second early: no settlement of it is earlier, whatever the
     * small skew between the database's clock and the simulator's.
     */
    private static Instant acceptedAt(UUID payment) throws SQLException {
        return orchestratorRow(payment).acceptedAt().minusSeconds(1);
    }

    private record OrchestratorRow(String txId, Instant acceptedAt) {}

    private static OrchestratorRow orchestratorRow(UUID payment) throws SQLException {
        try (var orchestrator = LocalDatabases.payments(); var statement = orchestrator.prepareStatement(
                "SELECT tx_id, accepted_at FROM payments WHERE payment_id = ?")) {
            statement.setObject(1, payment);
            try (var rs = statement.executeQuery()) {
                rs.next();
                return new OrchestratorRow(rs.getString(1), rs.getTimestamp(2).toInstant());
            }
        }
    }
}
