package com.tpe.ledgerservice.application.account;

import com.tpe.messaging.outbox.OutboxWriter;
import com.tpe.messaging.worker.DueItemHandler;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Deletes a closed account's key from the compacted {@code accounts.events} (specs/12-ledger-service.md §4, ADR-007):
 * 13 months after its close, a tombstone through the outbox, recorded on the account so it goes out once. Run by a
 * daily {@code PollingWorker} on the due accounts, each locked {@code FOR UPDATE SKIP LOCKED} in the worker's
 * transaction, so any instance may run it.
 */
@Service
public class ClosedAccountTombstones implements DueItemHandler<UUID> {

    private final AccountRepository accounts;
    private final OutboxWriter outbox;
    private final Clock clock;

    public ClosedAccountTombstones(AccountRepository accounts, OutboxWriter outbox, Clock clock) {
        this.accounts = accounts;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public void handle(UUID accountId) {
        if (accounts.markEventsTombstoned(accountId, clock.instant().truncatedTo(ChronoUnit.MILLIS))) {
            outbox.writeTombstone(AccountEvents.TOPIC, accountId.toString());
        }
    }
}
