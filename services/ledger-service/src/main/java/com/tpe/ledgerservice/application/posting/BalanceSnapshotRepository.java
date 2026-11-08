package com.tpe.ledgerservice.application.posting;

import com.tpe.ledgerservice.domain.posting.BalanceSnapshot;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** The {@code balance_snapshots} table and the postings sums behind a technical account's balance (12 §5, §6.3). */
public interface BalanceSnapshotRepository {

    /** The snapshot with the highest {@code up_to_posting_id}, if any. */
    Optional<BalanceSnapshot> latest(UUID accountId);

    /**
     * The highest {@code posting_id} of the account's committed postings created before {@code cutoff}, 0 without
     * one. The caller keeps the cutoff far enough back that no posting below it is still in flight.
     */
    long lastPostingBefore(UUID accountId, Instant cutoff);

    /** Σ(CR − DR) of the account's postings with {@code afterPostingId < posting_id <= upToPostingId}. */
    long sumBetween(UUID accountId, long afterPostingId, long upToPostingId);

    /**
     * Inserts the snapshot; one of the same boundary, taken by another run, keeps its balance (the same sum) and gets
     * the later {@code taken_at}. So each boundary is written once.
     */
    void save(BalanceSnapshot snapshot);

    /** The latest snapshot's balance plus Σ(CR − DR) of the postings after it; the full sum without a snapshot. */
    long derivedBalance(UUID accountId);
}
