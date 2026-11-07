package com.tpe.ledgerservice.application.posting;

/**
 * A customer account's row changed between read and version-checked write (specs/12-ledger-service.md §6.2). Thrown
 * out of the business transaction, which rolls back; {@link OptimisticRetry} runs the transaction again.
 */
public final class OptimisticConflict extends RuntimeException {

    public OptimisticConflict(String message) {
        super(message, null, false, false);
    }
}
