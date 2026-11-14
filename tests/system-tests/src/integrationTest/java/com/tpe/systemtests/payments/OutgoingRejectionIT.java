package com.tpe.systemtests.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.contracts.BlockReason;
import com.tpe.systemtests.harness.PaymentSystemTest;
import com.tpe.systemtests.harness.PaymentSystemTest.RejectedCount;
import com.tpe.systemtests.harness.TestData;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;

/**
 * The outgoing rejections of M2 end to end (specs/20-testing-strategy.md §4; UC-01, UC-04): what the customer sees
 * through payment-api, the {@code cause} of {@code tpe.payment.rejected} (20 §2: a refusal by a rule or an account state
 * is {@code BUSINESS}), and what is left of the hold. Without a remote VoP or CSM path in M2 every payee is one of our
 * customers, so the payments that pass risk and the payer's hold meet the payee on-us. The per-test invariants
 * (INV-3, -7, -8, -9, -11 beside the ledger's) run after each scenario. The webhook outcomes of 20 §4 (E2E-O-03:
 * "webhook to payer") and INV-13 come with the notifications track, as for E2E-O-02.
 */
@EnabledIfSystemProperty(named = "tpe.local-stack", matches = "true",
        disabledReason = "needs the compose stack; run with -Dtpe.local-stack=true")
class OutgoingRejectionIT extends PaymentSystemTest {

    private static final String BUSINESS = "BUSINESS";

    @Test
    void E2E_O_03_insufficientFundsAreRejectedAm04AndLeaveNoHold() {
        Party payer = customer(10_000);
        Party payee = customer(0);
        RejectedCount rejected = rejectedOutgoing("AM04");

        UUID payment = initiated(payer, payee.iban(), payee.name(), "123.45", reference(payer, payee));

        assertRejected(payer, payment, "AM04");
        assertNoHoldLeft(payment);
        rejected.assertTaggedOnce(BUSINESS);
        assertThat(available(payer)).isEqualTo(10_000);
    }

    @Test
    void E2E_O_04_aPayerBlockedByAnOperatorIsRejectedAc06() {
        Party payer = customer(50_000);
        Party payee = customer(0);
        String reference = reference(payer, payee);
        assertThat(ledger().block(OPERATOR, payer.account(), BlockReason.OPERATOR).status()).isEqualTo(200);
        RejectedCount rejected = rejectedOutgoing("AC06");

        UUID payment = initiated(payer, payee.iban(), payee.name(), "123.45", reference);

        assertRejected(payer, payment, "AC06");
        assertNoHoldLeft(payment);
        rejected.assertTaggedOnce(BUSINESS);
    }

    @Test
    void E2E_O_04_aClosedPayerIsRejectedAc04() {
        Party payer = customer(0);
        Party payee = customer(0);
        String reference = reference(payer, payee);
        assertThat(ledger().close(OPERATOR, payer.account()).status()).isEqualTo(200);
        RejectedCount rejected = rejectedOutgoing("AC04");

        UUID payment = initiated(payer, payee.iban(), payee.name(), "123.45", reference);

        assertRejected(payer, payment, "AC04");
        assertNoHoldLeft(payment);
        rejected.assertTaggedOnce(BUSINESS);
    }

    /** {@code AMOUNT_LIMIT}: denied before any hold, so the ledger was never asked. */
    @Test
    void E2E_O_05_anAmountOverTheLimitIsRejectedFr01WithoutALedgerCall() {
        Party payer = customer(5_000_002);
        Party payee = customer(0);
        RejectedCount rejected = rejectedOutgoing("FR01");

        UUID payment = initiated(payer, payee.iban(), payee.name(), "50000.01", reference(payer, payee));

        assertRejected(payer, payment, "FR01");
        assertNoHold(payment);
        rejected.assertTaggedOnce(BUSINESS);
    }

    @Test
    void E2E_O_15_aBlockedOnUsPayeeIsRejectedAc06AndTheHoldReleased() {
        Party payer = customer(50_000);
        Party payee = customer(0);
        String reference = reference(payer, payee);
        assertThat(ledger().block(OPERATOR, payee.account(), BlockReason.OPERATOR).status()).isEqualTo(200);
        RejectedCount rejected = rejectedOutgoing("AC06");

        UUID payment = initiated(payer, payee.iban(), payee.name(), "123.45", reference);

        assertRejected(payer, payment, "AC06");
        assertHoldReleased(payment);
        rejected.assertTaggedOnce(BUSINESS);
        assertThat(available(payer)).isEqualTo(50_000);
    }

    @Test
    void E2E_O_18_aPayeeIbanOfOurBankWithoutAnAccountIsRejectedAc01AndTheHoldReleased() {
        Party payer = customer(50_000);
        String unknown = TestData.unknownOwnIban();
        String name = TestData.newCustomer().name();
        // UC-03 2a: unknown to our accounts, so no answer about the name is possible
        String reference = verified(payer, unknown, name, "NOT_POSSIBLE").get("vopReference").asString();
        RejectedCount rejected = rejectedOutgoing("AC01");

        UUID payment = initiated(payer, unknown, name, "123.45", reference);

        assertRejected(payer, payment, "AC01");
        assertHoldReleased(payment);
        rejected.assertTaggedOnce(BUSINESS);
    }

    @Test
    void E2E_O_18_aClosedPayeeIsRejectedAc04AndTheHoldReleased() {
        Party payer = customer(50_000);
        Party payee = customer(0);
        assertThat(ledger().close(OPERATOR, payee.account()).status()).isEqualTo(200);
        // UC-03 2a: once vop-service's projection has the account closed
        String reference = verified(payer, payee.iban(), payee.name(), "NOT_POSSIBLE").get("vopReference").asString();
        RejectedCount rejected = rejectedOutgoing("AC04");

        UUID payment = initiated(payer, payee.iban(), payee.name(), "123.45", reference);

        assertRejected(payer, payment, "AC04");
        assertHoldReleased(payment);
        rejected.assertTaggedOnce(BUSINESS);
    }

    /**
     * {@code MULE_NEW_ACCOUNT} (14 §6.1): a new account's 11th incoming payment within 24 h is denied, the limit
     * including the payment assessed. The ten before it are settled on-us; only {@code ALLOW}ed ones count.
     */
    @Test
    void E2E_O_18_aNewPayeeThatTripsMuleNewAccountIsRejectedFr01AndTheHoldReleased() {
        Party payer = customer(50_000);
        Party payee = customer(0);
        for (int i = 0; i < 10; i++) {
            UUID earlier = initiated(payer, payee.iban(), payee.name(), "1.00", reference(payer, payee));
            assertThat(finalStatus(payer, earlier).get("status").asString()).as("payment %d", i + 1).isEqualTo("ACSC");
        }
        RejectedCount rejected = rejectedOutgoing("FR01");

        UUID payment = initiated(payer, payee.iban(), payee.name(), "1.00", reference(payer, payee));

        assertRejected(payer, payment, "FR01");
        assertHoldReleased(payment);
        rejected.assertTaggedOnce(BUSINESS);
        assertThat(booked(payee)).isEqualTo(1_000);
    }

    /**
     * {@code NEW_PAYEE_HIGH_AMOUNT} (14 §6.1): {@code REVIEW} is {@code FR01} before any hold. Adapted to M2 (issue #80):
     * the counterparty is one of our customers, verified with a misspelt name, so the VoP result is a local
     * {@code CLOSE_MATCH}.
     */
    @Test
    void E2E_O_19_aFirstHighPaymentToACloseMatchIsRejectedFr01WithoutALedgerCall() {
        Party payer = customer(700_000);
        Party payee = customer(0);
        String misspelt = TestData.closeMatchOf(payee.name());
        JsonNode verification = verified(payer, payee.iban(), misspelt, "CLOSE_MATCH");
        assertThat(verification.get("actualName").asString()).isEqualTo(payee.name());
        RejectedCount rejected = rejectedOutgoing("FR01");

        UUID payment = initiated(payer, payee.iban(), misspelt, "6000.00", verification.get("vopReference").asString());

        assertRejected(payer, payment, "FR01");
        assertNoHold(payment);
        rejected.assertTaggedOnce(BUSINESS);
    }

    /** The variant of E2E-O-19: with VoP {@code MATCH} the rule does not apply, and the payment settles on-us. */
    @Test
    void E2E_O_19_aFirstHighPaymentToAMatchIsSettled() {
        Party payer = customer(700_000);
        Party payee = customer(0);

        UUID payment = initiated(payer, payee.iban(), payee.name(), "6000.00", reference(payer, payee));

        assertThat(finalStatus(payer, payment).get("status").asString()).isEqualTo("ACSC");
        assertThat(booked(payee)).isEqualTo(600_000);
    }

    /** A reference for {@code payee} by its exact name: {@code MATCH} once vop-service's projection has it. */
    private String reference(Party payer, Party payee) {
        return verified(payer, payee.iban(), payee.name(), "MATCH").get("vopReference").asString();
    }

    /** {@code RJCT} with {@code reason} through payment-api, as JSON and as {@code pain.002}. */
    private void assertRejected(Party payer, UUID payment, String reason) {
        JsonNode status = finalStatus(payer, payment);
        assertThat(status.get("status").asString()).as("%s", status).isEqualTo("RJCT");
        assertThat(status.get("reasonCode").asString()).isEqualTo(reason);
        assertThat(status.get("internalState").asString()).isEqualTo("REJECTED");
        String pain002 = paymentApi().getText(payer.token(), "/v1/payments/" + payment + "/pain.002",
                "application/xml").body();
        assertThat(pain002).contains("<TxSts>RJCT</TxSts>", "<Cd>" + reason + "</Cd>");
    }

    /** The ledger was never asked for a hold of the payment. */
    private static void assertNoHold(UUID payment) {
        assertThat(hold(payment).status()).as("hold of %s", payment).isEqualTo(404);
    }

    /** No hold is left: the ledger refused it, or it was released (INV-3 checks it after the test as well). */
    private static void assertNoHoldLeft(UUID payment) {
        if (hold(payment).status() != 404) {
            assertHoldReleased(payment);
        }
    }

    private static void assertHoldReleased(UUID payment) {
        var hold = hold(payment);
        assertThat(hold.status()).as("hold of %s: %s", payment, hold.body()).isEqualTo(200);
        assertThat(hold.body().get("status").asString()).isEqualTo("RELEASED");
    }
}
