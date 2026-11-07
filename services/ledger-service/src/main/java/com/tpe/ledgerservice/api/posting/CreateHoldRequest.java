package com.tpe.ledgerservice.api.posting;

import com.tpe.ledgerservice.api.FieldError;
import com.tpe.ledgerservice.api.ValidationFailedException;
import com.tpe.ledgerservice.application.reservation.CreateHold;
import com.tpe.paymentdomain.DomainValidationException;
import com.tpe.paymentdomain.Iban;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /v1/holds} (specs/12-ledger-service.md §3.1). A currency other than EUR passes here and is refused by
 * the ledger with {@code 422 CURRENCY_NOT_SUPPORTED}.
 */
record CreateHoldRequest(UUID holdId, String debtorIban, Long amountMinor, String currency, Reference reference) {

    static final int MAX_TX_ID = 35;

    record Reference(UUID paymentId, String txId) {}

    /** @throws ValidationFailedException with one error per invalid field */
    CreateHold toCommand() {
        List<FieldError> errors = new ArrayList<>();
        if (holdId == null) {
            errors.add(new FieldError("holdId", "REQUIRED"));
        }
        Iban iban = null;
        if (debtorIban == null || debtorIban.isBlank()) {
            errors.add(new FieldError("debtorIban", "REQUIRED"));
        } else {
            try {
                iban = new Iban(debtorIban);
            } catch (DomainValidationException e) {
                errors.add(new FieldError("debtorIban", e.code().name()));
            }
        }
        if (amountMinor == null) {
            errors.add(new FieldError("amountMinor", "REQUIRED"));
        } else if (amountMinor <= 0) {
            errors.add(new FieldError("amountMinor", "INVALID_AMOUNT"));
        }
        if (currency == null || currency.isBlank()) {
            errors.add(new FieldError("currency", "REQUIRED"));
        }
        String txId = reference == null ? null : reference.txId();
        if (txId != null && txId.length() > MAX_TX_ID) {
            errors.add(new FieldError("reference.txId", "TOO_LONG"));
        }
        if (!errors.isEmpty()) {
            throw new ValidationFailedException(errors);
        }
        return new CreateHold(holdId, iban, amountMinor, currency, reference == null ? null : reference.paymentId(),
                txId);
    }
}
