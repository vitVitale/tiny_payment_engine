package com.tpe.systemtests.harness;

import com.tpe.contracts.AccountEvent;
import com.tpe.contracts.AccountSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Stands in for the vop / risk projections of {@code accounts.events} (specs/20-testing-strategy.md §4, E2E-A-01) and
 * behaves as ADR-007 asks of them: upsert by {@code accountId} only if {@code revision} is newer than the stored one,
 * delete on a tombstone ({@code null} value). It also records every event it received, ignored ones included, so a
 * test can see what reached the topic. Thread-safe: the consumer thread applies, test threads read.
 */
public final class AccountsProjection {

    /** The type recorded for a tombstone. */
    public static final String TOMBSTONE = "tombstone";

    /** A projected account: the snapshot of the newest event applied. */
    public record Account(long revision, AccountSnapshot snapshot) {}

    /** An event as it arrived; {@code revision} is 0 for a tombstone. */
    public record Received(String type, long revision) {}

    private final Map<UUID, Account> accounts = new HashMap<>();
    private final Map<UUID, List<Received>> received = new HashMap<>();

    /** Applies the record of key {@code accountId}; {@code event} is {@code null} for a tombstone. */
    public synchronized void apply(UUID accountId, AccountEvent event) {
        received.computeIfAbsent(accountId, id -> new ArrayList<>())
                .add(event == null ? new Received(TOMBSTONE, 0) : new Received(event.type(), event.revision()));
        if (event == null) {
            accounts.remove(accountId);
            return;
        }
        Account stored = accounts.get(accountId);
        if (stored == null || event.revision() > stored.revision()) {
            accounts.put(accountId, new Account(event.revision(), event.account()));
        }
    }

    /** The projected account, empty if none was applied or it was deleted. */
    public synchronized Optional<Account> account(UUID accountId) {
        return Optional.ofNullable(accounts.get(accountId));
    }

    /** Every event received for the account, in arrival order. */
    public synchronized List<Received> received(UUID accountId) {
        return List.copyOf(received.getOrDefault(accountId, List.of()));
    }
}
