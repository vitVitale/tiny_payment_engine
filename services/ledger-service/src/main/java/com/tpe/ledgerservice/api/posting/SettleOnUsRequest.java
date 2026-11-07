package com.tpe.ledgerservice.api.posting;

import com.tpe.ledgerservice.api.FieldError;
import com.tpe.ledgerservice.api.ValidationFailedException;
import com.tpe.paymentdomain.DomainValidationException;
import com.tpe.paymentdomain.Iban;
import java.util.List;

/** {@code POST /v1/holds/{holdId}/settle-on-us} (specs/12-ledger-service.md §3.1). */
record SettleOnUsRequest(String creditorIban) {

    /** @throws ValidationFailedException when the IBAN is missing or invalid */
    Iban toIban() {
        if (creditorIban == null || creditorIban.isBlank()) {
            throw new ValidationFailedException(List.of(new FieldError("creditorIban", "REQUIRED")));
        }
        try {
            return new Iban(creditorIban);
        } catch (DomainValidationException e) {
            throw new ValidationFailedException(List.of(new FieldError("creditorIban", e.code().name())));
        }
    }
}
