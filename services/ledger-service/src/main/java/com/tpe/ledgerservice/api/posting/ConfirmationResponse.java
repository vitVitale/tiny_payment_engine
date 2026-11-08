package com.tpe.ledgerservice.api.posting;

import com.tpe.ledgerservice.domain.reservation.PendingCredit;
import com.tpe.ledgerservice.domain.reservation.PendingCreditStatus;
import java.time.Instant;
import java.util.UUID;

/** A confirmed pending credit (specs/12-ledger-service.md §3.1): booked to {@code accountId} by the journal entry. */
record ConfirmationResponse(UUID pendingCreditId, PendingCreditStatus status, UUID journalEntryId, UUID accountId,
        Instant confirmedAt) {

    static ConfirmationResponse of(PendingCredit confirmed) {
        return new ConfirmationResponse(confirmed.pendingCreditId(), confirmed.status(), confirmed.entryId(),
                confirmed.accountId(), confirmed.finalisedAt());
    }
}
