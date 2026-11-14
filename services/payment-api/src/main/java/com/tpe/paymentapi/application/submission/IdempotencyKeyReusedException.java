package com.tpe.paymentapi.application.submission;

/**
 * The client used its {@code Idempotency-Key} for another request: {@code 422 IDEMPOTENCY_KEY_REUSED} (UC-01 2b).
 * Here the orchestrator found it ({@code 409 COMMAND_CONFLICT}), as the key's stored response is gone or the
 * guard missed it.
 */
public final class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String message) {
        super(message);
    }
}
