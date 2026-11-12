package com.tpe.vopservice.application.accounts;

import com.tpe.vopservice.domain.accounts.AccountHolder;
import java.time.Instant;
import java.util.UUID;

/** The {@code account_holders} projection (specs/13-vop-service.md §5). */
public interface AccountHolders {

    /**
     * Inserts {@code holder}, or replaces the stored row if its revision is older; a stored revision equal or newer
     * wins (02 §5).
     */
    void upsertIfNewer(AccountHolder holder, String normalisedName, Instant updatedAt);

    /** Deletes the account's row, if there is one. */
    void delete(UUID accountId);
}
