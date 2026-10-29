package com.tpe.messaging;

import com.tpe.messaging.outbox.JdbcOutboxRepository;
import com.tpe.messaging.outbox.OutboxRow;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * An outbox repository whose relay can be frozen: after {@link #freeze}, the next read returns its batch only after
 * {@link #thaw}, so the publish loop holds a batch it read and makes no progress, as in a paused JVM.
 */
public final class FreezableOutboxRepository extends JdbcOutboxRepository {

    private final AtomicInteger reads = new AtomicInteger();
    private volatile CountDownLatch thawed;
    private volatile CountDownLatch holding = new CountDownLatch(1);

    public FreezableOutboxRepository(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public List<OutboxRow> oldestUnpublished(Duration visibilityWindow, int limit) {
        List<OutboxRow> rows = super.oldestUnpublished(visibilityWindow, limit);
        reads.incrementAndGet();
        CountDownLatch thaw = thawed;
        if (thaw != null && !rows.isEmpty()) {
            holding.countDown();
            try {
                thaw.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return rows;
    }

    /** The next non-empty read blocks until {@link #thaw}. */
    public void freeze() {
        holding = new CountDownLatch(1);
        thawed = new CountDownLatch(1);
    }

    /** Waits until the relay holds a batch it read. */
    public void awaitHolding(Duration timeout) {
        try {
            if (!holding.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("relay did not read a batch within " + timeout);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** Lets the blocked read return its batch. */
    public void thaw() {
        CountDownLatch thaw = thawed;
        thawed = null;
        if (thaw != null) {
            thaw.countDown();
        }
    }

    /** Batches read so far. */
    public int reads() {
        return reads.get();
    }
}
