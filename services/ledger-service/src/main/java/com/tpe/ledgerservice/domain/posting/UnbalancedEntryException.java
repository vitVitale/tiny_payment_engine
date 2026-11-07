package com.tpe.ledgerservice.domain.posting;

/**
 * A journal entry the engine refuses before insert (specs/12-ledger-service.md §5): fewer than 2 postings, or
 * Σ DR ≠ Σ CR. A programming error, never a business refusal: every operation builds balanced entries.
 */
public final class UnbalancedEntryException extends IllegalArgumentException {

    public UnbalancedEntryException(String message) {
        super(message);
    }
}
