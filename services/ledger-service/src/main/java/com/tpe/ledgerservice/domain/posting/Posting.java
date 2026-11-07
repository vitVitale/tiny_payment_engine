package com.tpe.ledgerservice.domain.posting;

import java.util.Objects;
import java.util.UUID;

/** One line of a journal entry (specs/12-ledger-service.md §5): an account, a side and a positive EUR minor amount. */
public record Posting(UUID accountId, Side side, long amountMinor) {

    public Posting {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(side, "side");
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("a posting amount must be positive");
        }
    }
}
