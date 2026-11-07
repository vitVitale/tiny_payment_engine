package com.tpe.ledgerservice.api.account;

import com.tpe.ledgerservice.api.FieldError;
import com.tpe.ledgerservice.api.ValidationFailedException;
import com.tpe.ledgerservice.domain.account.BlockReason;
import java.util.ArrayList;
import java.util.List;

/** {@code POST /v1/accounts/{accountId}/block} and {@code /unblock} (specs/12-ledger-service.md §3.2). */
record BlockRequest(String reason, String comment) {

    static final int MAX_COMMENT = 500;

    /** @throws ValidationFailedException a missing or unknown reason, a comment over 500 characters */
    BlockReason validReason() {
        List<FieldError> errors = new ArrayList<>();
        BlockReason parsed = null;
        if (reason == null || reason.isBlank()) {
            errors.add(new FieldError("reason", "REQUIRED"));
        } else {
            try {
                parsed = BlockReason.valueOf(reason);
            } catch (IllegalArgumentException e) {
                errors.add(new FieldError("reason", "INVALID_REASON"));
            }
        }
        if (comment != null && comment.length() > MAX_COMMENT) {
            errors.add(new FieldError("comment", "TOO_LONG"));
        }
        if (!errors.isEmpty()) {
            throw new ValidationFailedException(errors);
        }
        return parsed;
    }
}
