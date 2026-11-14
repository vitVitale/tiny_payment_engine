package com.tpe.paymentapi.application.submission;

/**
 * vop-service could not check the reference: {@code 503 VOP_UNAVAILABLE} with {@code Retry-After}.
 */
public final class VopUnavailableException extends RuntimeException {

    public VopUnavailableException(String message) {
        super(message);
    }
}
