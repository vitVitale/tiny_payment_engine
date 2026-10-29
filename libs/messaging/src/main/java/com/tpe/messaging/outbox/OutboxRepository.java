package com.tpe.messaging.outbox;

import java.time.Duration;
import java.util.List;

/** The relay's access to the {@code outbox} table (specs/01-shared-libraries.md §3.3). */
public interface OutboxRepository {

    /**
     * The oldest unpublished rows in {@code id} order, never skipping one, committed at least {@code visibilityWindow}
     * ago: {@code BIGSERIAL} ids can commit out of order.
     */
    List<OutboxRow> oldestUnpublished(Duration visibilityWindow, int limit);

    /** Marks rows published, after Kafka acknowledged each of them. */
    void markPublished(List<Long> ids);
}
