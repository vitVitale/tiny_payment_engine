package com.tpe.vopservice.application.retention;

import com.tpe.messaging.worker.DueItemHandler;
import com.tpe.vopservice.application.references.VopResults;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Service;

/**
 * Retention of verification results (specs/13-vop-service.md §6.3, Q-09): a row 30 days old is deleted; a younger
 * one past {@code result-retention} after its expiry loses its names. Run by the hourly {@code PollingWorker} on the
 * due rows, each locked {@code FOR UPDATE SKIP LOCKED} in the worker's transaction, so any instance may run it.
 */
@Service
public class VopRetention implements DueItemHandler<String> {

    /** 13 §6.3: rows are deleted 30 days after they were created. */
    public static final Duration ROW_LIFETIME = Duration.ofDays(30);

    private final VopResults results;
    private final Clock clock;

    public VopRetention(VopResults results, Clock clock) {
        this.results = results;
        this.clock = clock;
    }

    @Override
    public void handle(String vopReference) {
        if (!results.deleteIfCreatedBefore(vopReference, clock.instant().minus(ROW_LIFETIME))) {
            results.clearNames(vopReference);
        }
    }
}
