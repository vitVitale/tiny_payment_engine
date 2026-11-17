package com.tpe.csmgateway.application.transmit;

import com.tpe.csmgateway.application.GatewayMetrics;
import com.tpe.csmgateway.application.transmit.TransmissionQueue.Claim;
import com.tpe.csmgateway.domain.transmit.Backoff;
import com.tpe.observability.context.Spans;
import com.tpe.paymentdomain.MsgId;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Transmits the rows of one {@link TransmissionQueue} to the CSM (specs/15-csm-gateway.md §6.5, ADR-011): claim (a
 * conditional update that counts the attempt and takes a lease, and refuses a row past its give-up window), then the
 * HTTP call with no lock held, then {@code DELIVERED} on {@code 2xx}, or the next attempt after
 * {@link Backoff#after(int)}. A claim that dies with its instance is taken again once its lease runs out. Every
 * attempt sends the same envelope, so the CSM deduplicates by its {@code MsgId}.
 */
public class Transmitter {

    /** The rows one poll handles at most. */
    static final int BATCH = 100;

    private static final Logger log = LoggerFactory.getLogger(Transmitter.class);

    private final TransmissionQueue queue;
    private final CsmClient csm;
    private final Clock clock;
    private final Duration lease;
    private final Executor executor;
    private final Spans spans;
    private final GatewayMetrics metrics;

    /**
     * @param executor runs each transmission on its own thread: the fast path off the committing thread, whose
     *     connection is still bound, and a poll's rows side by side, so one slow CSM call holds up no other row
     */
    public Transmitter(TransmissionQueue queue, CsmClient csm, Clock clock, Duration lease, Executor executor,
            Spans spans, GatewayMetrics metrics) {
        this.queue = queue;
        this.csm = csm;
        this.clock = clock;
        this.lease = lease;
        this.executor = executor;
        this.spans = spans;
        this.metrics = metrics;
    }

    /** The {@code afterCommit} fast path: the first attempt, soon, on the transmit executor. */
    public void transmitSoon(MsgId msgId) {
        executor.execute(() -> attemptOrGiveUp(msgId, false));
    }

    /**
     * {@link #transmitSoon} once the current transaction commits: the row is visible to the claim, and nothing is
     * sent for a transaction that rolls back. The worker's poll is the fallback.
     */
    public void transmitAfterCommit(MsgId msgId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                transmitSoon(msgId);
            }
        });
    }

    /**
     * One poll of the worker: every due row is attempted or, past its give-up window, given up, all side by side.
     * Returns when all of them are done, so polls do not overlap.
     */
    public void transmitDue() {
        CompletableFuture.allOf(queue.due(clock.instant(), BATCH).stream()
                .map(msgId -> CompletableFuture.runAsync(() -> attemptOrGiveUp(msgId, true), executor))
                .toArray(CompletableFuture[]::new)).join();
    }

    private void attemptOrGiveUp(MsgId msgId, boolean mayGiveUp) {
        try {
            if (!transmit(msgId) && mayGiveUp) {
                queue.giveUpIfExpired(msgId, clock.instant());
            }
        } catch (RuntimeException e) {
            log.error("transmission of {} failed", msgId.value(), e);
        }
    }

    /**
     * One attempt, outside any transaction: each step commits on its own.
     *
     * @return whether this caller claimed the row
     */
    boolean transmit(MsgId msgId) {
        Claim claim = queue.claim(msgId, clock.instant(), lease).orElse(null);
        if (claim == null) {
            return false;
        }
        metrics.transmitAttempt(queue.messageType());
        // a transmission continues the payment's trace (ADR-014 §2)
        Span span = (claim.traceContext() == null ? spans.newTrace("csm.transmit")
                : spans.childOf("csm.transmit", claim.traceContext())).start();
        try (Tracer.SpanInScope ignored = spans.inScope(span)) {
            csm.send(claim.envelope());
        } catch (CsmClient.CsmUnavailableException e) {
            span.error(e);
            Instant retryAt = clock.instant().plus(Backoff.after(claim.attempt()));
            // the message is the status or the exception type only, never the CSM's body (00 §11)
            log.warn("CSM did not take {} (attempt {}), next attempt at {}: {}", msgId.value(), claim.attempt(),
                    retryAt, e.getMessage());
            queue.retryAt(msgId, claim.attempt(), retryAt);
            return true;
        } finally {
            span.end();
        }
        queue.delivered(msgId, clock.instant());
        return true;
    }
}
