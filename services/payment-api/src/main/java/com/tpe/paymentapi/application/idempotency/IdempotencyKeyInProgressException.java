package com.tpe.paymentapi.application.idempotency;

/**
 * Another request with the same {@code Idempotency-Key} is being processed: {@code 409 IDEMPOTENCY_KEY_IN_PROGRESS}
 * (specs/10-payment-api.md §5.1, §3.5).
 */
public final class IdempotencyKeyInProgressException extends RuntimeException {

    public IdempotencyKeyInProgressException(String message) {
        super(message);
    }
}
