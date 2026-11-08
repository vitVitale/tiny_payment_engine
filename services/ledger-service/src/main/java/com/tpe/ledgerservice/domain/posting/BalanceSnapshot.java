package com.tpe.ledgerservice.domain.posting;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A technical account's balance up to and including a posting (specs/12-ledger-service.md §6.3, ADR-005): the
 * account's row is never booked, so its balance is the latest snapshot plus Σ(CR − DR) of the postings after
 * {@code upToPostingId}. Posting ids start at 1, so the snapshot before any posting has boundary 0 and balance 0.
 *
 * @param balanceMinor Σ CR − Σ DR of the account's postings up to and including {@code upToPostingId}
 * @param takenAt when it was last taken or confirmed: no posting at or below the boundary can still arrive
 */
public record BalanceSnapshot(UUID accountId, long upToPostingId, long balanceMinor, Instant takenAt) {

    public BalanceSnapshot {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(takenAt, "takenAt");
    }

    /** The snapshot before the account's first posting: boundary 0, balance 0, never taken. */
    public static BalanceSnapshot none(UUID accountId) {
        return new BalanceSnapshot(accountId, 0, 0, Instant.EPOCH);
    }

    /**
     * The snapshot after this one: {@code sinceMinor} is Σ(CR − DR) of the postings after {@link #upToPostingId} up to
     * and including {@code upToPostingId}.
     *
     * @throws IllegalArgumentException when the boundary does not move forward
     */
    public BalanceSnapshot next(long upToPostingId, long sinceMinor, Instant takenAt) {
        if (upToPostingId <= this.upToPostingId) {
            throw new IllegalArgumentException("a snapshot boundary only moves forward");
        }
        return new BalanceSnapshot(accountId, upToPostingId, Math.addExact(balanceMinor, sinceMinor), takenAt);
    }

    /** This snapshot confirmed as of {@code at}: no new posting has settled since it was taken. */
    public BalanceSnapshot confirmedAt(Instant at) {
        return new BalanceSnapshot(accountId, upToPostingId, balanceMinor, at);
    }
}
