package com.tpe.ledgerservice.api.posting;

import com.tpe.ledgerservice.domain.reservation.IncomingReference;
import com.tpe.ledgerservice.domain.reservation.PendingCredit;
import com.tpe.ledgerservice.domain.reservation.PendingCreditStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * A pending credit (specs/12-ledger-service.md §3.1), with the journal entry that confirmed it. A tombstone
 * ({@code tombstone: true}) has no account, no amount and no reference; absent optional fields are left out.
 */
record PendingCreditResponse(
        UUID pendingCreditId,
        UUID accountId,
        PendingCreditStatus status,
        Long amountMinor,
        UUID paymentId,
        String txId,
        String debtorAgentBic,
        Instant createdAt,
        Instant finalisedAt,
        UUID journalEntryId,
        boolean tombstone) {

    static PendingCreditResponse of(PendingCredit credit) {
        IncomingReference reference = credit.reference() == null
                ? new IncomingReference(null, null, null)
                : credit.reference();
        return new PendingCreditResponse(credit.pendingCreditId(), credit.accountId(), credit.status(),
                credit.amountMinor(), reference.paymentId(), reference.txId(), reference.debtorAgentBic(),
                credit.createdAt(), credit.finalisedAt(), credit.entryId(), credit.tombstone());
    }
}
