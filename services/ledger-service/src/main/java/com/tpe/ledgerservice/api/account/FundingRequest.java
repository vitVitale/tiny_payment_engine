package com.tpe.ledgerservice.api.account;

import com.tpe.ledgerservice.api.FieldError;
import com.tpe.ledgerservice.api.ValidationFailedException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code POST /v1/accounts/{accountId}/funding} (specs/12-ledger-service.md §3.2). A currency other than EUR passes
 * here and is refused by the ledger with {@code 422 CURRENCY_NOT_SUPPORTED}.
 */
record FundingRequest(Long amountMinor, String currency) {

    /** @throws ValidationFailedException a missing or non-positive amount, a missing currency */
    void validate() {
        List<FieldError> errors = new ArrayList<>();
        if (amountMinor == null) {
            errors.add(new FieldError("amountMinor", "REQUIRED"));
        } else if (amountMinor <= 0) {
            errors.add(new FieldError("amountMinor", "INVALID_AMOUNT"));
        }
        if (currency == null || currency.isBlank()) {
            errors.add(new FieldError("currency", "REQUIRED"));
        }
        if (!errors.isEmpty()) {
            throw new ValidationFailedException(errors);
        }
    }
}
