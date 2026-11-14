package com.tpe.paymentapi.application.idempotency;

import com.tpe.contracts.payment.PaymentAccepted;
import com.tpe.contracts.vop.VopResult;
import java.time.Instant;
import java.util.Optional;

/**
 * The {@code idempotency_keys} table (specs/10-payment-api.md §5.1). Every method is one statement of its own: no
 * transaction spans a downstream call. A row's {@code createdAt} identifies the request that holds it, so a request
 * whose key was taken over cannot release the new holder's row.
 */
public interface IdempotencyKeys {

    /**
     * {@code SUBMITTED}: no request holds the key, but a submit under it may have reached the orchestrator (a timeout
     * or a {@code 5xx}); the row keeps the reference check's result for the retry.
     */
    enum Status { IN_PROGRESS, SUBMITTED, COMPLETED }

    /**
     * @param response the {@code 202}'s payment; {@code null} unless {@code COMPLETED}
     * @param vopResult the reference check's result a submit carried; {@code null} unless it may have arrived
     */
    record Entry(String requestHash, Status status, PaymentAccepted response, Instant createdAt, Instant expiresAt,
            VopResult vopResult) {

        public Entry(String requestHash, Status status, PaymentAccepted response, Instant createdAt,
                Instant expiresAt) {
            this(requestHash, status, response, createdAt, expiresAt, null);
        }
    }

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

    /**
     * The key's row, if still {@code IN_PROGRESS} since {@code createdAt}, becomes {@code SUBMITTED} with the
     * {@code vopResult} its submit carried.
     */
    void submitted(IdempotentRequest request, Instant createdAt, VopResult vopResult);

    /**
     * The key's row, still {@code SUBMITTED} since {@code submittedAt}, is {@code IN_PROGRESS} again, for
     * {@code request}, since {@code createdAt}; its {@code vopResult} and {@code expiresAt} stay.
     *
     * @return {@code false} when the row changed meanwhile: another retry resumed it
     */
    boolean resume(IdempotentRequest request, Instant submittedAt, Instant createdAt);

    /** Deletes at most {@code limit} rows expired before {@code now}; returns how many. */
    int deleteExpired(Instant now, int limit);
}
