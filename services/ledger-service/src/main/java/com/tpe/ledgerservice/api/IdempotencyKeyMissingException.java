package com.tpe.ledgerservice.api;

/** A mutating request without {@code Idempotency-Key}: {@code 400 IDEMPOTENCY_KEY_MISSING} (12 §6.1). */
public final class IdempotencyKeyMissingException extends RuntimeException {

    public IdempotencyKeyMissingException() {
        super("Idempotency-Key header missing");
    }
}
