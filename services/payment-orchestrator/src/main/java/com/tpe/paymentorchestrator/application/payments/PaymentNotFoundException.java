package com.tpe.paymentorchestrator.application.payments;

import com.tpe.paymentdomain.PaymentId;

/** No payment with this id: {@code 404 PAYMENT_NOT_FOUND}. */
public final class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(PaymentId paymentId) {
        super("no payment " + paymentId.value());
    }
}
