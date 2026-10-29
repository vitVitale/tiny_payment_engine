package com.tpe.messaging.outbox;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * The relay's leadership (ADR-006 amendment): the publish loop reports progress, the lease thread reports heartbeats
 * and revokes a stalled loop. Each {@link #begin} starts a new term; a heartbeat outcome or revocation of an earlier
 * term is ignored, so a late answer on an old lock connection cannot end a newer lease. The lease holds from
 * {@link #begin} until a heartbeat fails, it is revoked, or the last good heartbeat is as old as the lock session
 * timeout: by then the server has ended the silent session, as after a JVM freeze. Thread-safe.
 */
final class LeaderLease {

    private final long stallAfterNanos;
    private final long sessionTimeoutNanos;
    private final LongSupplier nanoTime;

    private long term;
    private boolean leading;
    private boolean valid;
    private long lastProgress;
    private long lastHeartbeat;

    LeaderLease(Duration stallAfter, Duration sessionTimeout, LongSupplier nanoTime) {
        this.stallAfterNanos = stallAfter.toNanos();
        this.sessionTimeoutNanos = sessionTimeout.toNanos();
        this.nanoTime = nanoTime;
    }

    /** The lock was taken: a new term, and the publish loop starts. */
    synchronized long begin() {
        long now = nanoTime.getAsLong();
        lastProgress = now;
        lastHeartbeat = now;
        valid = true;
        leading = true;
        return ++term;
    }

    /** The publish loop left: leadership ended. */
    synchronized void end() {
        leading = false;
        valid = false;
    }

    /** The current term, for the lease thread's heartbeat. */
    synchronized long term() {
        return term;
    }

    /** The publish loop moved: an iteration completed, a failed send included, or it is waiting on Kafka acks. */
    synchronized void progress() {
        lastProgress = nanoTime.getAsLong();
    }

    synchronized void heartbeatSucceeded(long term) {
        if (term == this.term) {
            lastHeartbeat = nanoTime.getAsLong();
        }
    }

    /** One failure ends the lease. */
    synchronized void heartbeatFailed(long term) {
        if (term == this.term) {
            valid = false;
        }
    }

    /** The loop stalled: ends the lease; whether it was still valid, so the caller closes the lock connection once. */
    synchronized boolean revoke(long term) {
        if (term != this.term || !valid) {
            return false;
        }
        valid = false;
        return true;
    }

    /** Whether this instance leads: the loop may publish its next batch. */
    synchronized boolean isLeader() {
        return leading && valid && nanoTime.getAsLong() - lastHeartbeat < sessionTimeoutNanos;
    }

    /** Whether the publish loop is in charge and has not moved for {@code stallAfter}. */
    synchronized boolean isStalled() {
        return leading && nanoTime.getAsLong() - lastProgress > stallAfterNanos;
    }

    /** Not live while the publish loop is stalled; a follower is live. */
    synchronized boolean isLive() {
        return !isStalled();
    }
}
