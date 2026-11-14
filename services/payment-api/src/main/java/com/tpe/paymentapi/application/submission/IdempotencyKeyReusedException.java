package com.tpe.paymentapi.application.submission;

/**
 * The client used its {@code Idempotency-Key} for another request: {@code 422 IDEMPOTENCY_KEY_REUSED} (UC-01 2b).
 * Found by the idempotency guard (the key's stored response is for another hash) or by the orchestrator
 * ({@code 409 COMMAND_CONFLICT}), when the key's stored response is gone.
 */
public final class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String message) {
        super(message);
    }
}
