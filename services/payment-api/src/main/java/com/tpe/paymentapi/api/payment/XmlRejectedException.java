package com.tpe.paymentapi.api.payment;

import com.tpe.paymentapi.application.validation.FieldError;
import java.util.List;
import java.util.UUID;

/**
 * A {@code pain.001} that failed validation (UC-01 3a): {@code 400 VALIDATION_FAILED} with its errors and the
 * {@code rejectionId} of its {@code pain.002 RJCT FF01} (specs/10-payment-api.md §3.5).
 */
public final class XmlRejectedException extends RuntimeException {

    private final UUID rejectionId;
    private final List<FieldError> errors;

    XmlRejectedException(UUID rejectionId, List<FieldError> errors) {
        super("pain.001 rejected FF01 as " + rejectionId + ": " + errors);
        this.rejectionId = rejectionId;
        this.errors = List.copyOf(errors);
    }

    public UUID rejectionId() {
        return rejectionId;
    }

    public List<FieldError> errors() {
        return errors;
    }
}
