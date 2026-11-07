package com.tpe.ledgerservice.application.idempotency;

import com.tpe.ledgerservice.domain.LedgerError;
import com.tpe.ledgerservice.domain.LedgerException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The idempotency guard of specs/12-ledger-service.md §6.1, which every mutating Posting / Account API request goes
 * through. In one transaction: look up the key (same hash → replay, other hash → {@code IDEMPOTENCY_CONFLICT}), run
 * the business change, store the response — so key and effect commit together.
 *
 * <ul>
 *   <li>{@code 2xx}: stored in the business transaction, which commits.
 *   <li>{@code 422}: the business transaction rolls back (a refusal changes nothing); the response is stored in a
 *       transaction of its own. Equivalent to storing it "after the business change" of §6.1, as there is none.
 *   <li>Anything else the action returns (state conflicts): rolled back, not stored. An exception ({@code 5xx}, a
 *       {@code 400}) rolls back and propagates, nothing stored, so a retry runs again.
 * </ul>
 *
 * Two concurrent requests with the same new key: the second insert waits for the first transaction; once that
 * commits, the second rolls its business change back, looks the key up again and replays. Every response this
 * returns is the stored form, so a first response and its replays are the same JSON.
 */
@Service
public class IdempotencyGuard {

    /**
     * Each attempt either answers or lost the key to a request that committed it; the next one then replays. Two
     * would do; the third only guards against a store that keeps refusing, which ends in an exception.
     */
    private static final int MAX_ATTEMPTS = 3;

    private final IdempotencyKeys keys;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public IdempotencyGuard(IdempotencyKeys keys, PlatformTransactionManager transactionManager, Clock clock) {
        this.keys = keys;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @param action the business change and its response; MUST join the caller's transaction (its
     *     {@code @Transactional} services do by default)
     * @throws LedgerException {@code IDEMPOTENCY_CONFLICT}: the key was used for another request
     */
    public StoredResponse execute(IdempotentRequest request, Supplier<StoredResponse> action) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Outcome outcome;
            try {
                outcome = transactions.execute(status -> {
                    Optional<StoredResponse> stored = replay(request);
                    if (stored.isPresent()) {
                        return new Outcome(stored.get(), true);
                    }
                    StoredResponse response = action.get();
                    if (!response.isSuccess()) {
                        // A refusal that passed a joined @Transactional marked the transaction global rollback-only;
                        // the local mark makes the template roll back quietly instead of throwing
                        // UnexpectedRollbackException on commit.
                        status.setRollbackOnly();
                        return new Outcome(response, false);
                    }
                    return new Outcome(keys.insertIfAbsent(request, response, now())
                            .orElseThrow(KeyTakenConcurrently::new), true);
                });
            } catch (KeyTakenConcurrently e) {
                continue;
            }
            StoredResponse response = outcome.response();
            if (outcome.settled() || !response.storable()) {
                return response;
            }
            Optional<StoredResponse> stored = transactions.execute(status ->
                    keys.insertIfAbsent(request, response, now()));
            if (stored.isPresent()) {
                return stored.get();
            }
        }
        throw new IllegalStateException("idempotency key neither stored nor found after " + MAX_ATTEMPTS + " attempts");
    }

    /** The stored response of {@code request}'s key, if any; a different request under that key is a conflict. */
    private Optional<StoredResponse> replay(IdempotentRequest request) {
        return keys.find(request.key()).map(stored -> {
            if (!stored.requestHash().equals(request.requestHash())) {
                throw new LedgerException(LedgerError.IDEMPOTENCY_CONFLICT,
                        "the Idempotency-Key was first used for a different request");
            }
            return stored.response();
        });
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    /** @param settled replayed, or stored with the business change: nothing left to store */
    private record Outcome(StoredResponse response, boolean settled) {}

    /** Rolls the business transaction back: another request committed the same key first. */
    private static final class KeyTakenConcurrently extends RuntimeException {
        KeyTakenConcurrently() {
            super(null, null, false, false);
        }
    }
}
