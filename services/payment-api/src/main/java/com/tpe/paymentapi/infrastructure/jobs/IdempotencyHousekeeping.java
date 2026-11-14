package com.tpe.paymentapi.infrastructure.jobs;

import com.tpe.paymentapi.application.idempotency.IdempotencyRetention;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Deletes the expired idempotency keys every hour (specs/10-payment-api.md §5.1), started and stopped with the
 * application context. Every instance runs it; the deletes are idempotent.
 */
@Component
class IdempotencyHousekeeping implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyHousekeeping.class);

    static final Duration INTERVAL = Duration.ofHours(1);
    /** The first purge waits a little, so it does not compete with the start of the service. */
    static final Duration INITIAL_DELAY = Duration.ofMinutes(1);

    private final IdempotencyRetention retention;

    private ScheduledExecutorService scheduler;

    IdempotencyHousekeeping(IdempotencyRetention retention) {
        this.retention = retention;
    }

    @Override
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("idempotency-housekeeping").daemon().factory());
        // with a fixed delay; a run that throws is logged, not cancelled
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                int deleted = retention.purge();
                if (deleted > 0) {
                    LOG.info("Idempotency retention deleted {} keys", deleted);
                }
            } catch (RuntimeException e) {
                LOG.warn("Idempotency retention failed, trying again in {}: {}", INTERVAL, e.toString());
            }
        }, INITIAL_DELAY.toMillis(), INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
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
}
