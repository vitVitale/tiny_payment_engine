package com.tpe.paymentapi.api.payment;

import java.util.regex.Pattern;

/** The {@code Idempotency-Key} header (specs/10-payment-api.md §3.1, §3.5). */
final class IdempotencyKeys {

    static final String HEADER = "Idempotency-Key";
    static final String MISSING = "IDEMPOTENCY_KEY_MISSING";
    static final String INVALID = "IDEMPOTENCY_KEY_INVALID";

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private IdempotencyKeys() {}

    /** @throws IdempotencyKeyException {@code 400} for an absent or blank key, or one not of 1–64 {@code [A-Za-z0-9_-]} */
    static void require(String key) {
        if (key == null || key.isBlank()) {
            throw new IdempotencyKeyException(MISSING, "The Idempotency-Key header is required");
        }
        if (!FORMAT.matcher(key).matches()) {
            throw new IdempotencyKeyException(INVALID, "The Idempotency-Key must be 1-64 characters [A-Za-z0-9_-]");
        }
    }
}
