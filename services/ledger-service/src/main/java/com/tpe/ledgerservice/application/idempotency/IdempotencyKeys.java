package com.tpe.ledgerservice.application.idempotency;

import java.time.Instant;
import java.util.Optional;

/** The {@code idempotency_keys} table (specs/12-ledger-service.md §5). */
public interface IdempotencyKeys {

    /** A stored outcome: the hash of the request that produced it and its response. */
    record Stored(String requestHash, StoredResponse response) {}

    Optional<Stored> find(String key);

    /**
     * {@code INSERT … ON CONFLICT DO NOTHING}. While another open transaction holds the same key this waits for it;
     * if that transaction commits, nothing is inserted.
     *
     * @return the response as stored (and as {@link #find} returns it), empty when nothing was inserted
     */
    Optional<StoredResponse> insertIfAbsent(IdempotentRequest request, StoredResponse response, Instant createdAt);

    /** Deletes at most {@code limit} keys created before {@code cutoff}; returns how many. */
    int deleteCreatedBefore(Instant cutoff, int limit);
}
