package com.tpe.ledgerservice.api.account;

import com.tpe.ledgerservice.application.posting.Funded;
import java.time.Instant;
import java.util.UUID;

/** A booked funding: its journal entry and the account's balances after it (specs/12-ledger-service.md §3.2, §3.3). */
record FundingResponse(
        UUID journalEntryId,
        UUID accountId,
        long amountMinor,
        String currency,
        long bookedBalanceMinor,
        long availableBalanceMinor,
        Instant fundedAt) {

    static FundingResponse of(Funded funded) {
        var account = funded.account();
        return new FundingResponse(funded.entry().entryId(), account.accountId(), funded.amountMinor(),
                account.currency(), account.bookedMinor(), account.availableMinor(), funded.entry().createdAt());
    }
}
