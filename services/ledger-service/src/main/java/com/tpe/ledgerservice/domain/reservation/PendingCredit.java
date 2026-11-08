package com.tpe.ledgerservice.domain.reservation;

import com.tpe.ledgerservice.domain.LedgerError;
import com.tpe.ledgerservice.domain.LedgerException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A pending credit: a memo of an incoming payment we accepted but that has not settled yet (ADR-005,
 * specs/12-ledger-service.md §3.1). It is part of neither the booked nor the available balance; a confirm books it.
 * Its id is the incoming payment's {@code paymentId} (ADR-010). A <em>tombstone</em> is a pending credit cancelled
 * before it was ever created (ADR-009): status {@code CANCELLED}, no account, no amount; a later create of that id is
 * refused. Tombstones never count as active memos.
 *
 * @param accountId {@code null} for a tombstone
 * @param amountMinor {@code null} for a tombstone
 * @param reference {@code null} for a tombstone
 * @param finalisedAt when it was confirmed or cancelled, {@code null} while {@code ACTIVE}
 * @param entryId the journal entry that confirmed it, {@code null} unless {@code CONFIRMED}
 */
public record PendingCredit(
        UUID pendingCreditId,
        UUID accountId,
        Long amountMinor,
        PendingCreditStatus status,
        IncomingReference reference,
        Instant createdAt,
        Instant finalisedAt,
        UUID entryId,
        boolean tombstone) {

    public PendingCredit {
        Objects.requireNonNull(pendingCreditId, "pendingCreditId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** A new {@code ACTIVE} pending credit of a positive amount on {@code accountId}. */
    public static PendingCredit active(UUID pendingCreditId, UUID accountId, long amountMinor,
            IncomingReference reference, Instant createdAt) {
        Objects.requireNonNull(accountId, "accountId");
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("a pending credit amount must be positive");
        }
        return new PendingCredit(pendingCreditId, accountId, amountMinor, PendingCreditStatus.ACTIVE, reference,
                createdAt, null, null, false);
    }

    /** The record of a cancel that arrived before the create (ADR-009). */
    public static PendingCredit tombstone(UUID pendingCreditId, Instant at) {
        return new PendingCredit(pendingCreditId, null, null, PendingCreditStatus.CANCELLED, null, at, at, null, true);
    }

    /**
     * This pending credit as the answer to a create of its id: the {@code ACTIVE} credit itself when the create asks
     * for the same account and amount (a retry, {@code 200}).
     *
     * @param accountId the account the create asks for; {@code null} when its IBAN has no account
     * @throws LedgerException {@code PENDING_CREDIT_CANCELLED} (also for a tombstone), {@code PENDING_CREDIT_CONFIRMED},
     *     or {@code IDEMPOTENCY_CONFLICT} when the id was used for another account or amount
     */
    public PendingCredit replayFor(UUID accountId, long amountMinor) {
        return switch (status) {
            case CANCELLED -> throw cancelled();
            case CONFIRMED -> throw confirmed();
            case ACTIVE -> {
                if (!this.accountId.equals(accountId) || this.amountMinor.longValue() != amountMinor) {
                    throw new LedgerException(LedgerError.IDEMPOTENCY_CONFLICT,
                            "the pending credit id was first used for another account or amount");
                }
                yield this;
            }
        };
    }

    /**
     * The pending credit {@code CONFIRMED} at {@code at} by the journal entry {@code entryId}: the money is booked to
     * the payee. An already confirmed credit is returned as it is, with the entry that confirmed it first.
     *
     * @throws LedgerException {@code PENDING_CREDIT_CANCELLED} (also for a tombstone)
     */
    public PendingCredit confirm(UUID entryId, Instant at) {
        return switch (status) {
            case ACTIVE -> new PendingCredit(pendingCreditId, accountId, amountMinor, PendingCreditStatus.CONFIRMED,
                    reference, createdAt, at, Objects.requireNonNull(entryId, "entryId"), false);
            case CANCELLED -> throw cancelled();
            case CONFIRMED -> this;
        };
    }

    /**
     * The pending credit {@code CANCELLED} at {@code at}; the memo goes away. An already cancelled credit (or
     * tombstone) is returned as it is: a repeated cancel changes nothing.
     *
     * @throws LedgerException {@code PENDING_CREDIT_CONFIRMED}: the money is already booked
     */
    public PendingCredit cancel(Instant at) {
        return switch (status) {
            case ACTIVE -> new PendingCredit(pendingCreditId, accountId, amountMinor, PendingCreditStatus.CANCELLED,
                    reference, createdAt, at, null, false);
            case CONFIRMED -> throw confirmed();
            case CANCELLED -> this;
        };
    }

    /** Whether it is still a memo: {@code ACTIVE}. */
    public boolean active() {
        return status == PendingCreditStatus.ACTIVE;
    }

    private static LedgerException cancelled() {
        return new LedgerException(LedgerError.PENDING_CREDIT_CANCELLED, "the pending credit was cancelled");
    }

    private static LedgerException confirmed() {
        return new LedgerException(LedgerError.PENDING_CREDIT_CONFIRMED, "the pending credit was confirmed");
    }
}
