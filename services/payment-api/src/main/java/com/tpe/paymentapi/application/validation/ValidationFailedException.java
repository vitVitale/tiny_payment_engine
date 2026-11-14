package com.tpe.paymentapi.application.validation;

import java.util.List;

/** A request with invalid fields: {@code 400 VALIDATION_FAILED} with an {@code errors} list (UC-01 3a). */
public final class ValidationFailedException extends RuntimeException {

    private final List<FieldError> errors;

    public ValidationFailedException(List<FieldError> errors) {
        super("validation failed: " + errors);
        this.errors = List.copyOf(errors);
    }

    public List<FieldError> errors() {
        return errors;
    }
}
