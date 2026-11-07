package com.tpe.ledgerservice.domain.reservation;

import com.tpe.ledgerservice.domain.LedgerError;
import com.tpe.ledgerservice.domain.LedgerException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A hold: a memo that reserves money on a payer account, reducing its available balance but not its booked balance
 * (ADR-005, specs/12-ledger-service.md §3.1). Its id is the payment's {@code holdId}. A <em>tombstone</em> is a hold
 * released before it was ever created (ADR-009): status {@code RELEASED}, no account, no amount; a later create of
 * that id is refused. Tombstones never count as active memos.
 *
 * @param accountId {@code null} for a tombstone
 * @param amountMinor {@code null} for a tombstone
 * @param finalisedAt when it was settled or released, {@code null} while {@code ACTIVE}
 */
public record Hold(
        UUID holdId,
        UUID accountId,
        Long amountMinor,
        HoldStatus status,
        UUID paymentId,
        String txId,
        Instant createdAt,
        Instant finalisedAt,
        boolean tombstone) {

    public Hold {
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** A new {@code ACTIVE} hold of a positive amount on {@code accountId}. */
    public static Hold active(UUID holdId, UUID accountId, long amountMinor, UUID paymentId, String txId,
            Instant createdAt) {
        Objects.requireNonNull(accountId, "accountId");
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("a hold amount must be positive");
        }
        return new Hold(holdId, accountId, amountMinor, HoldStatus.ACTIVE, paymentId, txId, createdAt, null, false);
    }

    /** The record of a release that arrived before the create (ADR-009). */
    public static Hold tombstone(UUID holdId, Instant at) {
        return new Hold(holdId, null, null, HoldStatus.RELEASED, null, null, at, at, true);
    }

    /**
     * This hold as the answer to a create of its id: the {@code ACTIVE} hold itself when the create asks for the same
     * account and amount (a retry, {@code 200}).
     *
     * @param accountId the account the create asks for; {@code null} when its IBAN has no account
     * @throws LedgerException {@code HOLD_RELEASED} (also for a tombstone), {@code HOLD_SETTLED}, or
     *     {@code IDEMPOTENCY_CONFLICT} when the id was used for another account or amount
     */
    public Hold replayFor(UUID accountId, long amountMinor) {
        return switch (status) {
            case RELEASED -> throw new LedgerException(LedgerError.HOLD_RELEASED, "the hold was released");
            case SETTLED -> throw new LedgerException(LedgerError.HOLD_SETTLED, "the hold was settled");
            case ACTIVE -> {
                if (!this.accountId.equals(accountId) || this.amountMinor.longValue() != amountMinor) {
                    throw new LedgerException(LedgerError.IDEMPOTENCY_CONFLICT,
                            "the hold id was first used for another account or amount");
                }
                yield this;
            }
        };
    }

    /**
     * The hold {@code RELEASED} at {@code at}; its amount goes back to the available balance. An already released
     * hold (or tombstone) is returned as it is: a repeated release changes nothing.
     *
     * @throws LedgerException {@code HOLD_SETTLED}: the money has already left
     */
    public Hold release(Instant at) {
        return switch (status) {
            case ACTIVE -> new Hold(holdId, accountId, amountMinor, HoldStatus.RELEASED, paymentId, txId, createdAt,
                    at, false);
            case SETTLED -> throw new LedgerException(LedgerError.HOLD_SETTLED, "the hold was settled");
            case RELEASED -> this;
        };
    }

    /** Whether a release has something to give back: an {@code ACTIVE} hold. */
    public boolean active() {
        return status == HoldStatus.ACTIVE;
    }
}
