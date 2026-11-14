package com.tpe.paymentapi.application.idempotency;

import com.tpe.contracts.payment.PaymentAccepted;
import java.time.Instant;
import java.util.Optional;

/**
 * The {@code idempotency_keys} table (specs/10-payment-api.md §5.1). Every method is one statement of its own: no
 * transaction spans a downstream call. A row's {@code createdAt} identifies the request that holds it, so a request
 * whose key was taken over cannot release the new holder's row.
 */
public interface IdempotencyKeys {

    enum Status { IN_PROGRESS, COMPLETED }

    /** @param response the {@code 202}'s payment; {@code null} while {@code IN_PROGRESS} */
    record Entry(String requestHash, Status status, PaymentAccepted response, Instant createdAt, Instant expiresAt) {}

    /** {@code INSERT … IN_PROGRESS ON CONFLICT DO NOTHING}; {@code true} when inserted. */
    boolean claim(IdempotentRequest request, Instant createdAt, Instant expiresAt);

    Optional<Entry> find(String clientId, String key);

    /**
     * The key's row, still {@code IN_PROGRESS} since {@code abandonedAt}, becomes {@code request}'s.
     *
     * @return {@code false} when the row changed meanwhile: completed, released or taken over by another request
     */
    boolean takeOver(IdempotentRequest request, Instant abandonedAt, Instant createdAt, Instant expiresAt);

    /**
     * The key's row, if still {@code IN_PROGRESS} since {@code createdAt}, becomes {@code COMPLETED} with the
     * {@code 202}; a row taken over meanwhile is left to its new holder.
     */
    void complete(IdempotentRequest request, Instant createdAt, PaymentAccepted payment);

    /** Deletes the key's row if it is still {@code IN_PROGRESS} since {@code createdAt}. */
    void release(IdempotentRequest request, Instant createdAt);

    /** Deletes at most {@code limit} rows expired before {@code now}; returns how many. */
    int deleteExpired(Instant now, int limit);
}
