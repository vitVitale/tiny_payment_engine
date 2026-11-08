package com.tpe.ledgerservice.api.account;

import com.tpe.ledgerservice.domain.account.Account;
import com.tpe.ledgerservice.domain.account.AccountKind;
import com.tpe.ledgerservice.domain.account.AccountStatus;
import com.tpe.ledgerservice.domain.account.BlockReason;
import com.tpe.ledgerservice.domain.account.HolderType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * An account with its balances (specs/12-ledger-service.md §3.2, §3.3): {@code availableBalanceMinor =
 * booked − Σ active holds}; pending credits are listed separately in {@code pendingCreditsMinor} and are part of
 * neither balance. {@code activeBlockReasons} are the reasons the account is {@code BLOCKED} for. A {@code TECHNICAL}
 * account has no customer number and no holder, and its booked balance is derived from snapshots and postings (§6.3).
 */
record AccountResponse(
        UUID accountId,
        String iban,
        AccountKind kind,
        String customerId,
        HolderResponse holder,
        AccountStatus status,
        List<BlockReason> activeBlockReasons,
        long bookedBalanceMinor,
        long availableBalanceMinor,
        long pendingCreditsMinor,
        String currency,
        Instant openedAt,
        Instant closedAt) {

    record HolderResponse(
            HolderType type,
            String name,
            LocalDate dateOfBirth,
            String countryOfResidence,
            String organisationId) {}

    /** A technical account has no customer number and no holder; its balance is derived (12 §6.3). */
    static AccountResponse of(Account account) {
        var holder = account.holder();
        return new AccountResponse(
                account.accountId(),
                account.iban().value(),
                account.kind(),
                account.customerId() == null ? null : account.customerId().value(),
                holder == null ? null : new HolderResponse(holder.type(), holder.name().value(),
                        holder.dateOfBirth(), holder.countryOfResidence(), holder.organisationId()),
                account.status(),
                account.activeBlockReasons(),
                account.bookedMinor(),
                account.availableMinor(),
                account.pendingCreditsMinor(),
                account.currency(),
                account.openedAt(),
                account.closedAt());
    }
}
