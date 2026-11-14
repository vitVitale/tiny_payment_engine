package com.tpe.paymentapi.application.idempotency;

import com.tpe.contracts.payment.PaymentAccepted;
import com.tpe.paymentapi.application.status.PaymentStatuses;
import com.tpe.paymentapi.application.submission.IdempotencyKeyReusedException;
import com.tpe.paymentapi.application.submission.Orchestrator;
import com.tpe.paymentapi.application.submission.UnexpectedAnswerException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The idempotency guard of specs/10-payment-api.md §5.1 (UC-01 2a, 2b, 5a, 6a). It claims the key
 * ({@code IN_PROGRESS}) before the submit runs and stores only the {@code 202} ({@code COMPLETED}); a submit that
 * throws releases the key, as a {@code 400}, {@code 422} or {@code 503} had no side effect and the client may retry
 * with the same key. On a key already claimed:
 *
 * <ul>
 *   <li>{@code COMPLETED}, same hash: the stored payment, its state refreshed from the orchestrator (UC-01 2a); the
 *       stored one if that read fails.
 *   <li>{@code COMPLETED}, another hash: {@code 422 IDEMPOTENCY_KEY_REUSED} (UC-01 2b).
 *   <li>{@code IN_PROGRESS} younger than {@code in-progress-takeover}: {@code 409 IDEMPOTENCY_KEY_IN_PROGRESS};
 *       older: abandoned, taken over. Safe, as the orchestrator deduplicates by {@code commandId}.
 * </ul>
 *
 * No transaction spans the submit: each store call is a statement of its own, so a slow orchestrator holds no lock.
 * Records {@code tpe.api.idempotency{outcome=new|replay|reused|in_progress}} (§7).
 */
public class IdempotencyGuard {

    /** Each attempt claims, answers, or finds the key gone between claim and read; the next one claims it then. */
    private static final int MAX_ATTEMPTS = 3;

    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyGuard.class);

    private final IdempotencyKeys keys;
    private final PaymentStatuses statuses;
    private final Clock clock;
    private final Duration retention;
    private final Duration takeover;
    private final Counter created;
    private final Counter replayed;
    private final Counter reused;
    private final Counter inProgress;

    /**
     * @param retention {@code tpe.api.idempotency.retention}
     * @param takeover {@code tpe.api.idempotency.in-progress-takeover}
     */
    public IdempotencyGuard(IdempotencyKeys keys, PaymentStatuses statuses, Clock clock, Duration retention,
            Duration takeover, MeterRegistry meters) {
        this.keys = keys;
        this.statuses = statuses;
        this.clock = clock;
        this.retention = retention;
        this.takeover = takeover;
        this.created = outcome(meters, "new");
        this.replayed = outcome(meters, "replay");
        this.reused = outcome(meters, "reused");
        this.inProgress = outcome(meters, "in_progress");
    }

    /**
     * @param submit validation, the VoP reference check and the submit: what runs for a new key
     * @throws IdempotencyKeyReusedException the key was used for another request
     * @throws IdempotencyKeyInProgressException a request with the key is being processed
     */
    public PaymentAccepted initiate(IdempotentRequest request, Supplier<PaymentAccepted> submit) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Instant now = now();
            if (keys.claim(request, now, now.plus(retention))) {
                return run(request, now, submit);
            }
            Optional<IdempotencyKeys.Entry> found = keys.find(request.clientId(), request.key());
            if (found.isEmpty()) {
                continue;
            }
            IdempotencyKeys.Entry entry = found.get();
            if (entry.status() == IdempotencyKeys.Status.COMPLETED) {
                return replay(request, entry);
            }
            if (now.isBefore(entry.createdAt().plus(takeover))
                    || !keys.takeOver(request, entry.createdAt(), now, now.plus(retention))) {
                inProgress.increment();
                throw new IdempotencyKeyInProgressException("a request with the key is being processed");
            }
            LOG.info("took over an abandoned Idempotency-Key of client {}, in progress since {}", request.clientId(),
                    entry.createdAt());
            return run(request, now, submit);
        }
        throw new IllegalStateException("Idempotency-Key neither claimed nor found after " + MAX_ATTEMPTS
                + " attempts");
    }

    private PaymentAccepted run(IdempotentRequest request, Instant claimedAt, Supplier<PaymentAccepted> submit) {
        PaymentAccepted payment;
        try {
            payment = submit.get();
        } catch (RuntimeException e) {
            keys.release(request, claimedAt);
            throw e;
        }
        // a request whose key was taken over meanwhile stores nothing: the new holder's outcome is the key's
        keys.complete(request, claimedAt, payment);
        created.increment();
        return payment;
    }

    private PaymentAccepted replay(IdempotentRequest request, IdempotencyKeys.Entry entry) {
        if (!entry.requestHash().equals(request.requestHash())) {
            reused.increment();
            throw new IdempotencyKeyReusedException("the Idempotency-Key of client " + request.clientId()
                    + " has a stored response for another request");
        }
        replayed.increment();
        PaymentAccepted stored = entry.response();
        Orchestrator.Read read;
        try {
            read = statuses.read(stored.paymentId());
        } catch (UnexpectedAnswerException e) {
            // a bug on one side, logged where it was found; the replay has the stored state
            return stored;
        }
        return switch (read) {
            case Orchestrator.Found found -> new PaymentAccepted(stored.paymentId(), found.payment().state(),
                    found.payment().txId(), stored.acceptedAt());
            case Orchestrator.NotFound notFound -> {
                LOG.error("payment {} of a stored response is unknown to the orchestrator", stored.paymentId());
                yield stored;
            }
            case Orchestrator.Unavailable unavailable -> {
                LOG.warn("replaying the stored state of payment {}: {} {}", stored.paymentId(),
                        unavailable.failure(), unavailable.why());
                yield stored;
            }
        };
    }

    /** As PostgreSQL keeps it (at least), so a row's {@code createdAt} reads back equal. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static Counter outcome(MeterRegistry meters, String outcome) {
        return Counter.builder("tpe.api.idempotency").tag("outcome", outcome)
                .description("POST /v1/payments by Idempotency-Key outcome").register(meters);
    }
}
