package com.tpe.paymentorchestrator.application.outgoing;

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
import com.tpe.paymentorchestrator.domain.payment.OutgoingCommand;
import com.tpe.paymentorchestrator.domain.payment.OutgoingTimings;
import com.tpe.paymentorchestrator.domain.payment.PayeeVerification;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentParty;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Outgoing payments of the action tests, accepted at {@link #ACCEPTED}: {@code send_by} 10 s later. */
final class OutgoingPayments {

    static final Instant ACCEPTED = Instant.parse("2026-11-13T10:00:00Z");

    private OutgoingPayments() {}

    /** Submitted, {@code RECEIVED}; on-us to an own-bank payee, otherwise to the peer bank. */
    static Payment submitted(boolean onUs) {
        Iban creditorIban = new Iban(onUs ? "DE09100100100000000002" : "DE07200200200123456789");
        var command = new OutgoingCommand(new CommandId(UUID.randomUUID()), "mobile-app", new EndToEndId("INV-1"),
                new PartyName("Anna Schmidt"), new Iban("DE36100100100000000001"), new PartyName("Max Mustermann"),
                creditorIban, null, Money.parse("123.45"), null, "VOP-1", PayeeVerification.MATCH);
        return Payment.submitted(new PaymentId(UUID.randomUUID()), command, "0".repeat(64),
                new TxId("TPE01JABCDEFGHJKMNPQRSTVWXYZ"), new Uetr("6f1c2a3b-4d5e-4f60-8a1b-2c3d4e5f6a7b"),
                new PaymentParty(command.debtorName(), command.debtorIban(), new Bic("TPEBDEFFXXX")),
                new PaymentParty(command.creditorName(), command.creditorIban(),
                        new Bic(onUs ? "TPEBDEFFXXX" : "PEERDEFFXXX")),
                onUs, ACCEPTED, new OutgoingTimings(Duration.ofSeconds(10), Duration.ofSeconds(20),
                        Duration.ofSeconds(2)), null);
    }

    /** {@code RISK_CHECKED}, its {@code CREATE_HOLD} pending. */
    static Payment riskChecked(boolean onUs) {
        return submitted(onUs).riskChecked();
    }

    /** On-us, {@code FUNDS_RESERVED}, its {@code ASSESS_PAYEE_RISK} pending. */
    static Payment heldOnUs() {
        return riskChecked(true).fundsReserved(UUID.randomUUID());
    }

    /** On-us, {@code FUNDS_RESERVED}, its {@code SETTLE_ON_US} pending. */
    static Payment payeeChecked() {
        return heldOnUs().payeeRiskChecked();
    }

    /** On-us, {@code REJECTED AC06} by the payee check, its {@code RELEASE_HOLD} pending. */
    static Payment awaitingRelease() {
        return payeeChecked().rejected(ReasonCode.AC06, PendingAction.RELEASE_HOLD, ACCEPTED.plusSeconds(1));
    }
}
