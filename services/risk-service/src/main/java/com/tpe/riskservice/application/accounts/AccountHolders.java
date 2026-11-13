package com.tpe.riskservice.application.accounts;

import com.tpe.riskservice.domain.accounts.AccountHolder;
import java.time.Instant;
import java.util.UUID;

/**
 * The {@code account_holders} projection (specs/14-risk-service.md §5). The rules read it through the assessment's
 * history query ({@code Assessments.history}), in the same round trip.
 */
public interface AccountHolders {

    /**
     * Inserts {@code holder}, or replaces the stored row if its revision is older; a stored revision equal or newer
     * wins (02 §5).
     */
    void upsertIfNewer(AccountHolder holder, Instant updatedAt);

    /** Deletes the account's row, if there is one. */
    void delete(UUID accountId);
}
