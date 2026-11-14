package com.tpe.paymentorchestrator.application.runner;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code tpe.runner.*} (specs/11-payment-orchestrator.md §6.3, §7).
 *
 * @param lease how long a claimed action belongs to the runner that claimed it; after it the sweeper hands the action
 *     to any instance
 * @param maxBackoff the cap of the retry back-off
 * @param maxConcurrency how many actions run at once on this instance
 * @param safetyMargin what a remote call leaves before its deadline
 * @param stuckAfter how long a ledger action that is retried forever may stay pending before a
 *     {@code LEDGER_ACTION_STUCK} case is opened
 */
public record RunnerSettings(Duration lease, Duration maxBackoff, int maxConcurrency, Duration safetyMargin,
        Duration stuckAfter) {

    private static final Duration FIRST_BACKOFF = Duration.ofMillis(100);

    public RunnerSettings {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        Objects.requireNonNull(safetyMargin, "safetyMargin");
        Objects.requireNonNull(stuckAfter, "stuckAfter");
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("tpe.runner.max-concurrency must be at least 1");
        }
    }

    /** 100 ms after the first attempt, doubling with each further one, at most {@code maxBackoff}. */
    public Duration backoff(int attempts) {
        int doublings = Math.max(0, attempts - 1);
        if (doublings >= 30) {
            return maxBackoff;
        }
        Duration backoff = FIRST_BACKOFF.multipliedBy(1L << doublings);
        return backoff.compareTo(maxBackoff) > 0 ? maxBackoff : backoff;
    }

    /**
     * {@code min(configured, deadline − now − safetyMargin)}; empty when nothing is left, so the call is not made.
     *
     * @param deadline {@code null} for an action without one (settle, release, confirm, cancel)
     */
    public Optional<Duration> readTimeout(Duration configured, Instant deadline, Instant now) {
        if (deadline == null) {
            return Optional.of(configured);
        }
        Duration left = Duration.between(now, deadline).minus(safetyMargin);
        if (left.isNegative() || left.isZero()) {
            return Optional.empty();
        }
        return Optional.of(left.compareTo(configured) < 0 ? left : configured);
    }

    /** The action pending since {@code since} is stuck at {@code now}: {@code stuck-after} has passed. */
    public boolean stuck(Instant since, Instant now) {
        return !now.isBefore(since.plus(stuckAfter));
    }
}
