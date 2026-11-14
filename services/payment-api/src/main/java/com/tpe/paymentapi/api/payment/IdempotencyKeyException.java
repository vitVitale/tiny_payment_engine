package com.tpe.paymentapi.api.payment;

/** {@code 400 IDEMPOTENCY_KEY_MISSING} or {@code IDEMPOTENCY_KEY_INVALID} (specs/10-payment-api.md §3.5). */
public final class IdempotencyKeyException extends RuntimeException {

    private final String code;

    IdempotencyKeyException(String code, String detail) {
        super(detail);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
