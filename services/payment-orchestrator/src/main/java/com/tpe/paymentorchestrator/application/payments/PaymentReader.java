package com.tpe.paymentorchestrator.application.payments;

import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import org.springframework.stereotype.Service;

/** Reads one payment for {@code GET /v1/payments/{paymentId}} (specs/11-payment-orchestrator.md §3.1). */
@Service
public class PaymentReader {

    private final Payments payments;

    PaymentReader(Payments payments) {
        this.payments = payments;
    }

    /** @throws PaymentNotFoundException if there is none */
    public Payment get(PaymentId paymentId) {
        return payments.find(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }
}
