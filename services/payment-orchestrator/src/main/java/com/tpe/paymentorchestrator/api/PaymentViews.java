package com.tpe.paymentorchestrator.api;

import com.tpe.contracts.Direction;
import com.tpe.contracts.payment.PaymentState;
import com.tpe.contracts.payment.PaymentView;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentParty;

/** A payment as {@code GET /v1/payments/{paymentId}} shows it (specs/11-payment-orchestrator.md §3.1). */
public final class PaymentViews {

    private PaymentViews() {}

    public static PaymentView of(Payment payment) {
        return new PaymentView(payment.paymentId(), Direction.valueOf(payment.direction().name()), payment.onUs(),
                PaymentState.valueOf(payment.state().name()), payment.reasonCode(), payment.txId(), payment.uetr(),
                payment.endToEndId(), payment.amount(), holder(payment.debtor()), holder(payment.creditor()),
                payment.remittance(), payment.acceptedAt(), payment.finalisedAt());
    }

    private static PaymentView.Holder holder(PaymentParty party) {
        return new PaymentView.Holder(party.name(), party.iban());
    }
}
