package com.tpe.paymentapi.api.security;

/** A valid token without a claim the API needs (specs/00-engineering-baseline.md §12.1): {@code 403}. */
public final class InvalidTokenClaimsException extends RuntimeException {

    public InvalidTokenClaimsException(String message) {
        super(message);
    }
}
