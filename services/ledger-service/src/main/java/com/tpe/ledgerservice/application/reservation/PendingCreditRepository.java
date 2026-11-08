package com.tpe.ledgerservice.application.reservation;

import com.tpe.ledgerservice.domain.reservation.PendingCredit;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** The {@code pending_credits} table (specs/12-ledger-service.md §5), tombstones included. */
public interface PendingCreditRepository {

    Optional<PendingCredit> find(UUID pendingCreditId);

    /** {@link #find} with {@code SELECT … FOR UPDATE}: the row stays locked until the transaction ends. */
    Optional<PendingCredit> findForUpdate(UUID pendingCreditId);

    /**
     * {@code INSERT … ON CONFLICT DO NOTHING}: while another open transaction inserts the same id this waits for it;
     * if that one commits, nothing is inserted and {@link #find} then reads its row (ADR-009).
     *
     * @return whether this pending credit was inserted
     */
    boolean insertIfAbsent(PendingCredit pendingCredit);

    /** Writes a cancel of an {@code ACTIVE} pending credit; returns {@code false} when it is no longer {@code ACTIVE}. */
    boolean markCancelled(PendingCredit cancelled);

    /**
     * Writes a confirm of an {@code ACTIVE} pending credit with its journal entry; returns {@code false} when it is no
     * longer {@code ACTIVE}.
     */
    boolean markConfirmed(PendingCredit confirmed);

    /** Deletes at most {@code limit} tombstones created before {@code cutoff}; returns how many. */
    int deleteTombstonesCreatedBefore(Instant cutoff, int limit);
}
