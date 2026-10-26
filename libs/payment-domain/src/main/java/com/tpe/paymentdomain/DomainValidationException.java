package com.tpe.paymentdomain;

import java.util.Objects;

/**
 * Thrown by value-object constructors for invalid input. Callers switch on {@link #code()}; the message never
 * contains the rejected input, so it is safe to log (specs/26-observability.md §4.4).
 */
public final class DomainValidationException extends IllegalArgumentException {

    private final ValidationCode code;

    public DomainValidationException(ValidationCode code, String message) {
        super(code + ": " + message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ValidationCode code() {
        return code;
    }
}
