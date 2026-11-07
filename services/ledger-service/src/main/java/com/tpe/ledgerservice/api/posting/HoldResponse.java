package com.tpe.ledgerservice.api.posting;

import com.tpe.ledgerservice.domain.reservation.Hold;
import com.tpe.ledgerservice.domain.reservation.HoldStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * A hold (specs/12-ledger-service.md §3.1). A tombstone ({@code tombstone: true}) has no account and no amount; absent
 * optional fields are left out.
 */
record HoldResponse(
        UUID holdId,
        UUID accountId,
        HoldStatus status,
        Long amountMinor,
        UUID paymentId,
        String txId,
        Instant createdAt,
        Instant finalisedAt,
        boolean tombstone) {

    static HoldResponse of(Hold hold) {
        return new HoldResponse(hold.holdId(), hold.accountId(), hold.status(), hold.amountMinor(), hold.paymentId(),
                hold.txId(), hold.createdAt(), hold.finalisedAt(), hold.tombstone());
    }
}
