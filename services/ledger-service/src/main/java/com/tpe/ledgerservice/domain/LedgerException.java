package com.tpe.ledgerservice.domain;

import java.util.Objects;

/** A request the ledger refuses by a business rule; nothing was changed. */
public final class LedgerException extends RuntimeException {

    private final LedgerError error;

    public LedgerException(LedgerError error, String message) {
        super(error + ": " + message);
        this.error = Objects.requireNonNull(error, "error");
    }

    public LedgerError error() {
        return error;
    }
}
