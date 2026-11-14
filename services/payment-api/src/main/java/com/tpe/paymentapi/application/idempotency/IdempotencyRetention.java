package com.tpe.paymentapi.application.idempotency;

import java.time.Clock;
import java.time.Instant;

/**
 * Deletes the idempotency keys past their {@code expires_at}, {@code created_at + tpe.api.idempotency.retention}
 * (specs/10-payment-api.md §5.1). Deletes in chunks, each its own statement, so no purge holds locks for long; any
 * instance may run it at any time.
 */
public class IdempotencyRetention {

    static final int CHUNK = 10_000;

    private final IdempotencyKeys keys;
    private final Clock clock;

    public IdempotencyRetention(IdempotencyKeys keys, Clock clock) {
        this.keys = keys;
        this.clock = clock;
    }

    /** @return the number of keys deleted */
    public int purge() {
        Instant now = clock.instant();
        int total = 0;
        int deleted;
        do {
            deleted = keys.deleteExpired(now, CHUNK);
            total += deleted;
        } while (deleted == CHUNK);
        return total;
    }
}
