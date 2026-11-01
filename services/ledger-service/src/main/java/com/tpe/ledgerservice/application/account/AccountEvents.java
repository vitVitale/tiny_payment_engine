package com.tpe.ledgerservice.application.account;

import com.tpe.contracts.AccountKind;
import com.tpe.contracts.AccountOpened;
import com.tpe.contracts.AccountSnapshot;
import com.tpe.contracts.AccountStatus;
import com.tpe.contracts.Holder;
import com.tpe.contracts.HolderType;
import com.tpe.ledgerservice.domain.account.Account;
import java.util.List;

/**
 * The {@code accounts.events} messages of a customer account (specs/02-messaging-contracts.md §5, ADR-007): each
 * carries the full snapshot, keyed by the account id.
 */
final class AccountEvents {

    static final String TOPIC = "accounts.events";

    private AccountEvents() {}

    static AccountOpened opened(Account account) {
        return new AccountOpened(account.accountId(), account.revision(), account.openedAt(), snapshot(account));
    }

    private static AccountSnapshot snapshot(Account account) {
        var holder = account.holder();
        return new AccountSnapshot(
                account.iban(),
                account.customerId().value(),
                AccountStatus.valueOf(account.status().name()),
                List.of(),
                AccountKind.valueOf(account.kind().name()),
                new Holder(HolderType.valueOf(holder.type().name()), holder.name(), holder.dateOfBirth(),
                        holder.countryOfResidence(), holder.organisationId()),
                account.openedAt(),
                account.closedAt());
    }
}
