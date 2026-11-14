package com.tpe.paymentapi.api.payment;

import com.tpe.contracts.payment.PaymentAccepted;
import com.tpe.contracts.payment.PaymentState;
import com.tpe.paymentapi.application.mapping.CustomerStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code 202} of {@code POST /v1/payments} (specs/10-payment-api.md §3.1): the same body for a new payment and for
 * the payment of an earlier submit (UC-01 6a), its status as of now.
 */
public record PaymentResponse(UUID paymentId, CustomerStatus status, PaymentState internalState, Instant acceptedAt) {

    static PaymentResponse of(PaymentAccepted accepted) {
        return new PaymentResponse(accepted.paymentId().value(), CustomerStatus.of(accepted.state()),
                accepted.state(), accepted.acceptedAt());
    }
}
