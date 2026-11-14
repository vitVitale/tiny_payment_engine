package com.tpe.paymentapi.application.rejection;

/** Unknown, expired or another client's rejection, alike: {@code 404 REJECTION_NOT_FOUND}. */
public final class RejectionNotFoundException extends RuntimeException {

    public RejectionNotFoundException() {
        super("no such rejection");
    }
}
