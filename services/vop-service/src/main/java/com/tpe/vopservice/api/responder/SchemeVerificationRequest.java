package com.tpe.vopservice.api.responder;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.DomainValidationException;
import com.tpe.paymentdomain.Iban;
import com.tpe.vopservice.api.FieldError;
import com.tpe.vopservice.api.ValidationFailedException;
import com.tpe.vopservice.application.responding.SchemeVerification;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code POST /vop/v1/verifications} (specs/13-vop-service.md §3.2). Limits: {@code requestId} 64 characters (the
 * audit column), {@code name} 140 (an ISO 20022 name), {@code organisationId} 35 (an ISO identifier).
 */
record SchemeVerificationRequest(
        String requestId, String requestingPspBic, String iban, String name, String organisationId) {

    static final int MAX_REQUEST_ID = 64;
    static final int MAX_NAME = 140;
    static final int MAX_ORGANISATION_ID = 35;

    /** Every invalid field is a {@code 400 VALIDATION_FAILED}; a blank {@code organisationId} is none. */
    SchemeVerification toCommand(Bic requestingPsp) {
        List<FieldError> errors = new ArrayList<>();
        text("requestId", requestId, MAX_REQUEST_ID, errors);
        Iban payeeIban = null;
        if (iban == null || iban.isBlank()) {
            errors.add(new FieldError("iban", "REQUIRED"));
        } else {
            try {
                payeeIban = new Iban(iban);
            } catch (DomainValidationException e) {
                errors.add(new FieldError("iban", e.code().name()));
            }
        }
        text("name", name, MAX_NAME, errors);
        boolean hasOrganisationId = organisationId != null && !organisationId.isBlank();
        if (hasOrganisationId && organisationId.strip().length() > MAX_ORGANISATION_ID) {
            errors.add(new FieldError("organisationId", "TOO_LONG"));
        }
        if (!errors.isEmpty()) {
            throw new ValidationFailedException(errors);
        }
        return new SchemeVerification(requestId, requestingPsp, payeeIban, name,
                hasOrganisationId ? organisationId.strip() : null);
    }

    private static void text(String field, String value, int maxLength, List<FieldError> errors) {
        if (value == null || value.isBlank()) {
            errors.add(new FieldError(field, "REQUIRED"));
        } else if (value.length() > maxLength) {
            errors.add(new FieldError(field, "TOO_LONG"));
        }
    }
}
