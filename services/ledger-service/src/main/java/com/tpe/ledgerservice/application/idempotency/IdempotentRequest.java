package com.tpe.ledgerservice.application.idempotency;

import java.util.Objects;

/**
 * @param key the {@code Idempotency-Key} header, at most {@link #MAX_KEY_LENGTH} characters
 * @param requestHash SHA-256 of canonical JSON body + path (specs/12-ledger-service.md §5)
 */
public record IdempotentRequest(String key, String requestHash) {

    public static final int MAX_KEY_LENGTH = 200;

    public IdempotentRequest {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(requestHash, "requestHash");
        if (key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Idempotency-Key must have 1 to 200 characters");
        }
    }
}
