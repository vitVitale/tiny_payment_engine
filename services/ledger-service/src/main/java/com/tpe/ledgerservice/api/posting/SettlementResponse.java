package com.tpe.ledgerservice.api.posting;

import com.tpe.ledgerservice.application.reservation.Settlement;
import com.tpe.ledgerservice.domain.reservation.HoldStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * A settled hold (specs/12-ledger-service.md §3.1); {@code creditorAccountId} only for an on-us settlement.
 */
record SettlementResponse(UUID holdId, HoldStatus status, UUID journalEntryId, UUID creditorAccountId,
        Instant settledAt) {

    static SettlementResponse of(Settlement settlement) {
        var hold = settlement.hold();
        return new SettlementResponse(hold.holdId(), hold.status(), hold.entryId(), settlement.creditorAccountId(),
                hold.finalisedAt());
    }
}
