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

/** The moves of the outgoing risk step (specs/11-payment-orchestrator.md §6.2, {@code ASSESS_RISK}). */
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

    private static Payment submitted() {
        var command = new OutgoingCommand(new CommandId(UUID.randomUUID()), "mobile-app",
                new EndToEndId("INV-1"), new PartyName("Anna Schmidt"), new Iban("DE36100100100000000001"),
                new PartyName("Max Mustermann"), new Iban("DE07200200200123456789"), null, Money.parse("123.45"),
                null, "VOP-1", PayeeVerification.MATCH);
        return Payment.submitted(new PaymentId(UUID.randomUUID()), command, "0".repeat(64),
                new TxId("TPE01JABCDEFGHJKMNPQRSTVWXYZ"), new Uetr("6f1c2a3b-4d5e-4f60-8a1b-2c3d4e5f6a7b"),
                new PaymentParty(command.debtorName(), command.debtorIban(), new Bic("TPEBDEFFXXX")),
                new PaymentParty(command.creditorName(), command.creditorIban(), new Bic("PEERDEFFXXX")), false,
                ACCEPTED, new OutgoingTimings(Duration.ofSeconds(10), Duration.ofSeconds(20), Duration.ofSeconds(2)),
                null);
    }
}
