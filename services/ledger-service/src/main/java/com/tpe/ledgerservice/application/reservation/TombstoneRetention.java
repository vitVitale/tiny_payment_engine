package com.tpe.ledgerservice.application.reservation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Deletes hold tombstones older than {@code tpe.ledger.idempotency.retention} (ADR-009: kept as long as idempotency
 * keys, 30 d; a create retried after that is impossible, the orchestrator gives up long before). In chunks, each its
 * own statement; any instance may run it at any time.
 */
@Service
public class TombstoneRetention {

    static final int CHUNK = 10_000;

    private final HoldRepository holds;
    private final Duration retention;
    private final Clock clock;

    public TombstoneRetention(HoldRepository holds,
            @Value("${tpe.ledger.idempotency.retention}") Duration retention, Clock clock) {
        this.holds = holds;
        this.retention = retention;
        this.clock = clock;
    }

    /** @return the number of tombstones deleted */
    public int purge() {
        Instant cutoff = clock.instant().minus(retention);
        int total = 0;
        int deleted;
        do {
            deleted = holds.deleteTombstonesCreatedBefore(cutoff, CHUNK);
            total += deleted;
        } while (deleted == CHUNK);
        return total;
    }
}
