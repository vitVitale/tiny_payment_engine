package com.tpe.ledgerservice.api.account;

import com.tpe.ledgerservice.application.posting.AccountPosting;
import com.tpe.ledgerservice.application.posting.PostingsPage;
import com.tpe.ledgerservice.domain.posting.EntryType;
import com.tpe.ledgerservice.domain.posting.Side;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A page of {@code GET /v1/accounts/{accountId}/entries} (specs/12-ledger-service.md §3.2): the account's postings,
 * newest first; {@code nextCursor} is absent on the last page.
 */
record EntriesResponse(List<PostingResponse> postings, String nextCursor) {

    /** A posting with its journal entry; {@code paymentId} and {@code txId} are absent for funding. */
    record PostingResponse(
            UUID entryId,
            EntryType entryType,
            Side side,
            long amountMinor,
            UUID paymentId,
            String txId,
            Instant createdAt) {

        static PostingResponse of(AccountPosting posting) {
            return new PostingResponse(posting.entryId(), posting.entryType(), posting.side(), posting.amountMinor(),
                    posting.paymentId(), posting.txId(), posting.createdAt());
        }
    }

    static EntriesResponse of(PostingsPage page) {
        return new EntriesResponse(page.postings().stream().map(PostingResponse::of).toList(),
                page.nextBeforePostingId() == null ? null : EntriesCursor.encode(page.nextBeforePostingId()));
    }
}
