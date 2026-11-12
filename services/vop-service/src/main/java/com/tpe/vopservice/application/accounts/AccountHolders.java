package com.tpe.vopservice.application.accounts;

import com.tpe.paymentdomain.Iban;
import com.tpe.vopservice.domain.accounts.AccountHolder;
import com.tpe.vopservice.domain.accounts.ProjectedHolder;
import java.time.Instant;
import java.util.Optional;
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

    /** The holder of {@code iban}, whatever its status; empty if the projection has none (13 §6.2). */
    Optional<ProjectedHolder> findByIban(Iban iban);
}
