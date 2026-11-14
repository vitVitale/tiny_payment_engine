package com.tpe.paymentorchestrator.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.CommandId;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The moves of the outgoing steps (specs/11-payment-orchestrator.md §6.2): {@code ASSESS_RISK}, {@code CREATE_HOLD},
 * {@code ASSESS_PAYEE_RISK} and {@code SETTLE_ON_US}.
 */
class PaymentTest {

    private static final Instant ACCEPTED = Instant.parse("2026-11-13T10:00:00Z");

    @Test
    void anAllowedPaymentIsRiskCheckedAndHoldsNext() {
        Payment checked = submitted().riskChecked();

        assertThat(checked.state()).isEqualTo(PaymentState.RISK_CHECKED);
        assertThat(checked.pendingAction()).isEqualTo(PendingAction.CREATE_HOLD);
        assertThat(checked.reasonCode()).isNull();
        assertThat(checked.finalisedAt()).isNull();
    }

    @Test
    void aRejectionIsFinalWithItsReasonAndCompensation() {
        Instant at = ACCEPTED.plusMillis(300);

        Payment rejected = submitted().rejected(ReasonCode.FR01, null, at);

        assertThat(rejected.state()).isEqualTo(PaymentState.REJECTED);
        assertThat(rejected.reasonCode()).isEqualTo(ReasonCode.FR01);
        assertThat(rejected.pendingAction()).isNull();
        assertThat(rejected.finalisedAt()).isEqualTo(at);
        assertThat(submitted().riskChecked().rejected(ReasonCode.AB03, PendingAction.RELEASE_HOLD, at).pendingAction())
                .isEqualTo(PendingAction.RELEASE_HOLD);
    }

    @Test
    void aRejectedPaymentCannotBeCheckedAgain() {
        Payment rejected = submitted().rejected(ReasonCode.FR01, null, ACCEPTED);

        assertThatThrownBy(rejected::riskChecked).isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void aHeldOnUsPaymentAssessesThePayeeNext() {
        UUID payer = UUID.randomUUID();

        Payment reserved = submitted(true).riskChecked().fundsReserved(payer);

        assertThat(reserved.state()).isEqualTo(PaymentState.FUNDS_RESERVED);
        assertThat(reserved.pendingAction()).isEqualTo(PendingAction.ASSESS_PAYEE_RISK);
        assertThat(reserved.debtorAccountId()).isEqualTo(payer);
    }

    @Test
    void aHeldPaymentToAnotherBankIsSentNext() {
        Payment reserved = submitted().riskChecked().fundsReserved(UUID.randomUUID());

        assertThat(reserved.pendingAction()).isEqualTo(PendingAction.SEND);
    }

    @Test
    void anAllowedPayeeKeepsTheHoldAndSettlesOnUsNext() {
        Payment reserved = submitted(true).riskChecked().fundsReserved(UUID.randomUUID());

        Payment checked = reserved.payeeRiskChecked();

        assertThat(checked.state()).isEqualTo(PaymentState.FUNDS_RESERVED);
        assertThat(checked.pendingAction()).isEqualTo(PendingAction.SETTLE_ON_US);
    }

    @Test
    void onlyAHeldOnUsPaymentHasItsPayeeAssessed() {
        assertThatThrownBy(() -> submitted(true).riskChecked().payeeRiskChecked())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> submitted().riskChecked().fundsReserved(UUID.randomUUID()).payeeRiskChecked())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anOnUsSettlementIsFinalWithItsEntryAndPayeeAccount() {
        UUID entry = UUID.randomUUID();
        UUID payee = UUID.randomUUID();
        Instant at = ACCEPTED.plusMillis(800);

        Payment settled = submitted(true).riskChecked().fundsReserved(UUID.randomUUID()).payeeRiskChecked()
                .settledOnUs(entry, payee, at);

        assertThat(settled.state()).isEqualTo(PaymentState.SETTLED);
        assertThat(settled.pendingAction()).isNull();
        assertThat(settled.bookingEntryId()).isEqualTo(entry);
        assertThat(settled.creditorAccountId()).isEqualTo(payee);
        assertThat(settled.finalisedAt()).isEqualTo(at);
        assertThat(settled.reasonCode()).isNull();
    }

    private static Payment submitted() {
        return submitted(false);
    }

    private static Payment submitted(boolean onUs) {
        var command = new OutgoingCommand(new CommandId(UUID.randomUUID()), "mobile-app",
                new EndToEndId("INV-1"), new PartyName("Anna Schmidt"), new Iban("DE36100100100000000001"),
                new PartyName("Max Mustermann"), new Iban("DE07200200200123456789"), null, Money.parse("123.45"),
                null, "VOP-1", PayeeVerification.MATCH);
        return Payment.submitted(new PaymentId(UUID.randomUUID()), command, "0".repeat(64),
                new TxId("TPE01JABCDEFGHJKMNPQRSTVWXYZ"), new Uetr("6f1c2a3b-4d5e-4f60-8a1b-2c3d4e5f6a7b"),
                new PaymentParty(command.debtorName(), command.debtorIban(), new Bic("TPEBDEFFXXX")),
                new PaymentParty(command.creditorName(), command.creditorIban(), new Bic("PEERDEFFXXX")), onUs,
                ACCEPTED, new OutgoingTimings(Duration.ofSeconds(10), Duration.ofSeconds(20), Duration.ofSeconds(2)),
                null);
    }
}
