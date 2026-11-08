package com.tpe.ledgerservice.application.account;

import com.tpe.contracts.AccountBlocked;
import com.tpe.contracts.AccountClosed;
import com.tpe.contracts.AccountKind;
import com.tpe.contracts.AccountOpened;
import com.tpe.contracts.AccountSnapshot;
import com.tpe.contracts.AccountStatus;
import com.tpe.contracts.AccountUnblocked;
import com.tpe.contracts.Holder;
import com.tpe.contracts.HolderType;
import com.tpe.ledgerservice.domain.account.Account;
import com.tpe.ledgerservice.domain.account.BlockReason;
import java.time.Instant;

/**
 * The {@code accounts.events} messages of a customer account (specs/02-messaging-contracts.md §5, ADR-007): each
 * carries the full snapshot, keyed by the account id, and the account's revision after the change.
 */
final class AccountEvents {

    static final String TOPIC = "accounts.events";

    private AccountEvents() {}

    static AccountOpened opened(Account account) {
        return new AccountOpened(account.accountId(), account.revision(), account.openedAt(), snapshot(account));
    }

    static AccountBlocked blocked(Account account, BlockReason reason, Instant at) {
        return new AccountBlocked(account.accountId(), account.revision(), at, snapshot(account), contract(reason));
    }

    static AccountUnblocked unblocked(Account account, BlockReason reason, Instant at) {
        return new AccountUnblocked(account.accountId(), account.revision(), at, snapshot(account), contract(reason));
    }

    static AccountClosed closed(Account account) {
        return new AccountClosed(account.accountId(), account.revision(), account.closedAt(), snapshot(account));
    }

    private static AccountSnapshot snapshot(Account account) {
        var holder = account.holder();
        return new AccountSnapshot(
                account.iban(),
                account.customerId().value(),
                AccountStatus.valueOf(account.status().name()),
                account.activeBlockReasons().stream().map(AccountEvents::contract).toList(),
                AccountKind.valueOf(account.kind().name()),
                new Holder(HolderType.valueOf(holder.type().name()), holder.name(), holder.dateOfBirth(),
                        holder.countryOfResidence(), holder.organisationId()),
                account.openedAt(),
                account.closedAt());
    }

    private static com.tpe.contracts.BlockReason contract(BlockReason reason) {
        return com.tpe.contracts.BlockReason.valueOf(reason.name());
    }
}
