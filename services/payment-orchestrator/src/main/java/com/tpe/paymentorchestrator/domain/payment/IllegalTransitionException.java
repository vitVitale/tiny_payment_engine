package com.tpe.paymentorchestrator.domain.payment;

/** A move the payment state machine does not allow: a bug in the caller, never a business outcome. */
public final class IllegalTransitionException extends RuntimeException {

    public IllegalTransitionException(PaymentDirection direction, PaymentState from, PaymentState to) {
        super(direction + " payment cannot move from " + from + " to " + to);
    }
}
