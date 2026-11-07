package com.tpe.ledgerservice.application.posting;

import com.tpe.ledgerservice.domain.LedgerError;
import com.tpe.ledgerservice.domain.LedgerException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runs a whole business transaction again when it lost an optimistic-lock race on a customer account
 * (specs/12-ledger-service.md §6.2): up to {@code tpe.ledger.optimistic-retry.max-attempts} attempts in all, 5–20 ms
 * of jitter between them, then {@code 409 CONCURRENT_MODIFICATION}. Each conflict means another transaction on the
 * account committed, so with N concurrent writers an attempt loses at most N − 1 times.
 */
@Component
public class OptimisticRetry {

    static final int MIN_JITTER_MILLIS = 5;
    static final int MAX_JITTER_MILLIS = 20;

    /** Waits between attempts; a seam for tests. */
    @FunctionalInterface
    interface Pause {
        void millis(long millis) throws InterruptedException;
    }

    private final int maxAttempts;
    private final Pause pause;

    @Autowired
    public OptimisticRetry(@Value("${tpe.ledger.optimistic-retry.max-attempts}") int maxAttempts) {
        this(maxAttempts, Thread::sleep);
    }

    OptimisticRetry(int maxAttempts, Pause pause) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("tpe.ledger.optimistic-retry.max-attempts must be at least 1");
        }
        this.maxAttempts = maxAttempts;
        this.pause = pause;
    }

    /**
     * @param transaction opens and commits its own transaction; MUST NOT join a caller's, which a conflict would
     *     leave rolled back
     * @throws LedgerException {@code CONCURRENT_MODIFICATION} once every attempt conflicted
     */
    public <T> T run(Supplier<T> transaction) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.get();
            } catch (OptimisticConflict e) {
                if (attempt >= maxAttempts) {
                    throw new LedgerException(LedgerError.CONCURRENT_MODIFICATION,
                            "the account kept changing; retried " + maxAttempts + " times");
                }
                jitter();
            }
        }
    }

    private void jitter() {
        try {
            pause.millis(ThreadLocalRandom.current().nextLong(MIN_JITTER_MILLIS, MAX_JITTER_MILLIS + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted between optimistic retries", e);
        }
    }
}
