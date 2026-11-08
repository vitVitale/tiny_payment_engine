package com.tpe.ledgerservice.application.reservation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BiFunction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Deletes hold and pending-credit tombstones older than {@code tpe.ledger.idempotency.retention} (ADR-009: kept as
 * long as idempotency keys, 30 d; a create retried after that is impossible, the orchestrator gives up long before).
 * In chunks, each its own statement; any instance may run it at any time.
 */
@Service
public class TombstoneRetention {

    static final int CHUNK = 10_000;

    private final HoldRepository holds;
    private final PendingCreditRepository pendingCredits;
    private final Duration retention;
    private final Clock clock;

    public TombstoneRetention(HoldRepository holds, PendingCreditRepository pendingCredits,
            @Value("${tpe.ledger.idempotency.retention}") Duration retention, Clock clock) {
        this.holds = holds;
        this.pendingCredits = pendingCredits;
        this.retention = retention;
        this.clock = clock;
    }

    /** @return the number of tombstones deleted, of holds and pending credits together */
    public int purge() {
        Instant cutoff = clock.instant().minus(retention);
        return purge(cutoff, holds::deleteTombstonesCreatedBefore)
                + purge(cutoff, pendingCredits::deleteTombstonesCreatedBefore);
    }

    private static int purge(Instant cutoff, BiFunction<Instant, Integer, Integer> deleteChunk) {
        int total = 0;
        int deleted;
        do {
            deleted = deleteChunk.apply(cutoff, CHUNK);
            total += deleted;
        } while (deleted == CHUNK);
        return total;
    }
}
