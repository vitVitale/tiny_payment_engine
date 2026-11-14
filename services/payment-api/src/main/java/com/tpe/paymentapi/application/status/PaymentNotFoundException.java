package com.tpe.paymentapi.application.status;

/**
 * {@code 404 PAYMENT_NOT_FOUND} (specs/10-payment-api.md §3.2, UC-07 1a): unknown, incoming, or not the caller's; the
 * three are not told apart, so nothing leaks.
 */
public final class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException() {
        super("no payment of the caller with this id");
    }
}
