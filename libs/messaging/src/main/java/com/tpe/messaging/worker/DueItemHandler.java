package com.tpe.messaging.worker;

/** Handles one due row of a {@link PollingWorker} (specs/01-shared-libraries.md §3.5). */
@FunctionalInterface
public interface DueItemHandler<T> {

    /**
     * Runs inside the polling transaction while the row is locked. Must not do remote I/O: it records a decision or
     * enqueues work, e.g. marks the row {@code FIRED} or reschedules it, and remote calls happen after the commit
     * (00 §10). Throwing rolls back this item's changes only (a savepoint); the row stays due.
     */
    void handle(T item);
}
