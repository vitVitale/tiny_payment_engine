package com.tpe.messaging.housekeeping;

import com.tpe.messaging.outbox.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * On every instance, one background thread: counts the unpublished outbox rows for the gauge
 * {@code tpe.outbox.pending} every {@code pendingInterval}, so a scrape never waits on the database, and runs
 * {@link MessagingRetention} every {@code retentionInterval}. A failed run is logged and tried again next time.
 */
public final class MessagingHousekeeping implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MessagingHousekeeping.class);

    /** Pending every 5 s, retention every hour. */
    public record Intervals(Duration pending, Duration retention) {

        public static Intervals defaults() {
            return new Intervals(Duration.ofSeconds(5), Duration.ofHours(1));
        }
    }

    private final OutboxRepository outbox;
    private final MessagingRetention retention;
    private final Intervals intervals;
    private final AtomicLong pending = new AtomicLong();

    private ScheduledExecutorService scheduler;

    public MessagingHousekeeping(OutboxRepository outbox, MessagingRetention retention, Intervals intervals,
            MeterRegistry registry) {
        this.outbox = outbox;
        this.retention = retention;
        this.intervals = intervals;
        Gauge.builder("tpe.outbox.pending", pending, AtomicLong::get)
                .description("Outbox rows not yet published")
                .register(registry);
    }

    @Override
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("messaging-housekeeping").daemon().factory());
        schedule(this::countPending, Duration.ZERO, intervals.pending());
        // the first purge waits a little, so it does not compete with the start of the service
        schedule(this::purge, intervals.pending(), intervals.retention());
    }

    @Override
    public synchronized void stop() {
        if (scheduler == null) {
            return;
        }
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        scheduler = null;
    }

    @Override
    public synchronized boolean isRunning() {
        return scheduler != null;
    }

    /** Unpublished outbox rows at the last count. */
    public long pending() {
        return pending.get();
    }

    private void countPending() {
        pending.set(outbox.countUnpublished());
    }

    private void purge() {
        MessagingRetention.Purged purged = retention.purge();
        if (purged.outbox() > 0 || purged.inbox() > 0) {
            log.info("Messaging retention deleted {} outbox and {} inbox rows", purged.outbox(), purged.inbox());
        }
    }

    /** With a fixed delay; a task that throws is logged, not cancelled. */
    private void schedule(Runnable task, Duration initialDelay, Duration delay) {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.warn("Messaging housekeeping failed, trying again in {}: {}", delay, e.toString());
            }
        }, initialDelay.toMillis(), delay.toMillis(), TimeUnit.MILLISECONDS);
    }
}
