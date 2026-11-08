package com.tpe.ledgerservice.application.posting;

import com.tpe.ledgerservice.domain.posting.BalanceSnapshot;
import com.tpe.messaging.worker.DueItemHandler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Takes a technical account's next balance snapshot (specs/12-ledger-service.md §6.3, ADR-005). {@code BIGSERIAL} ids
 * can commit out of order, so the boundary is the last posting created more than {@link #SETTLE_TIME} ago: no
 * transaction lives that long ({@code spring.transaction.default-timeout} is 30 s), so one still open with a lower id
 * has by then committed or rolled back, and a posting committed late is never below a snapshot. Run by a
 * {@code PollingWorker} with the technical account's row locked {@code FOR NO KEY UPDATE SKIP LOCKED}, which postings
 * (their foreign key takes {@code FOR KEY SHARE}) never wait for. Each run marks the account checked, also when no new
 * posting has settled, so an idle account is not due again before the next interval.
 */
@Service
public class TechnicalAccountSnapshots implements DueItemHandler<UUID> {

    /** How old a posting must be to fall below a snapshot boundary (12 §6.3). */
    public static final Duration SETTLE_TIME = Duration.ofSeconds(60);

    private final BalanceSnapshotRepository snapshots;
    private final Clock clock;

    public TechnicalAccountSnapshots(BalanceSnapshotRepository snapshots, Clock clock) {
        this.snapshots = snapshots;
        this.clock = clock;
    }

    /**
     * Snapshots the account up to its last settled posting, starting from the empty snapshot before its first posting;
     * when nothing new has settled, the latest snapshot is confirmed as of now.
     */
    @Override
    public void handle(UUID accountId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        BalanceSnapshot latest = snapshots.latest(accountId).orElseGet(() -> BalanceSnapshot.none(accountId));
        long boundary = snapshots.lastPostingBefore(accountId, now.minus(SETTLE_TIME));
        snapshots.save(boundary <= latest.upToPostingId()
                ? latest.confirmedAt(now)
                : latest.next(boundary, snapshots.sumBetween(accountId, latest.upToPostingId(), boundary), now));
    }
}
