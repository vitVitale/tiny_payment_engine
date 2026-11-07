package com.tpe.ledgerservice.application.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Deletes idempotency keys older than {@code tpe.ledger.idempotency.retention} (30 d, specs/12-ledger-service.md §6.1,
 * §7). Deletes in chunks, each its own statement, so no purge holds locks for long; any instance may run it at any
 * time.
 */
@Service
public class IdempotencyRetention {

    static final int CHUNK = 10_000;

    private final IdempotencyKeys keys;
    private final Duration retention;
    private final Clock clock;

    public IdempotencyRetention(IdempotencyKeys keys,
            @Value("${tpe.ledger.idempotency.retention}") Duration retention, Clock clock) {
        this.keys = keys;
        this.retention = retention;
        this.clock = clock;
    }

    /** @return the number of keys deleted */
    public int purge() {
        Instant cutoff = clock.instant().minus(retention);
        int total = 0;
        int deleted;
        do {
            deleted = keys.deleteCreatedBefore(cutoff, CHUNK);
            total += deleted;
        } while (deleted == CHUNK);
        return total;
    }
}
