package com.tpe.ledgerservice.application.posting;

import com.tpe.ledgerservice.domain.posting.JournalEntry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code journal_entries} and {@code postings} (specs/12-ledger-service.md §5); postings are only ever inserted. */
public interface JournalRepository {

    /** Inserts the entry and its postings, in their order. */
    void insert(JournalEntry entry);

    /**
     * The postings of an account, newest (highest {@code posting_id}) first.
     *
     * @param from inclusive lower bound of {@code created_at}, or {@code null}
     * @param to exclusive upper bound of {@code created_at}, or {@code null}
     * @param beforePostingId only postings with a lower id, or {@code null} for the first page
     */
    List<AccountPosting> postings(UUID accountId, Instant from, Instant to, Long beforePostingId, int limit);
}
