package com.tpe.paymentapi.application.idempotency;

import com.tpe.contracts.payment.PaymentAccepted;
import com.tpe.contracts.vop.VopResult;
import com.tpe.paymentapi.application.status.PaymentStatuses;
import com.tpe.paymentapi.application.submission.IdempotencyKeyReusedException;
import com.tpe.paymentapi.application.submission.Orchestrator;
import com.tpe.paymentapi.application.submission.OrchestratorUnavailableException;
import com.tpe.paymentapi.application.submission.UnexpectedAnswerException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The idempotency guard of specs/10-payment-api.md §5.1 (UC-01 2a, 2b, 5a, 6a). It claims the key
 * ({@code IN_PROGRESS}) before the submit runs and stores only the {@code 202} ({@code COMPLETED}); a submit that
 * throws releases the key, as a {@code 400}, {@code 422} or {@code 503} had no side effect and the client may retry
 * with the same key. A {@code 503} whose submit may have reached the orchestrator (a timeout or a {@code 5xx}, UC-01
 * 6a) does not: the key becomes {@code SUBMITTED} with the reference check's {@code vopResult}, and stays so through
 * every failed retry until a {@code 202}. On a key already claimed:
 *
 * <ul>
 *   <li>{@code COMPLETED}, same hash: the stored payment, its state refreshed from the orchestrator (UC-01 2a); the
 *       stored one if that read fails.
 *   <li>{@code COMPLETED}, another hash: {@code 422 IDEMPOTENCY_KEY_REUSED} (UC-01 2b).
 *   <li>{@code IN_PROGRESS} younger than {@code in-progress-takeover}: {@code 409 IDEMPOTENCY_KEY_IN_PROGRESS};
 *       older: abandoned, taken over. Safe, as the orchestrator deduplicates by {@code commandId}.
 *   <li>{@code SUBMITTED}, same hash: resumed; the submit runs again with the stored {@code vopResult} and no new
 *       reference check, so a retry after the reference expired still answers the payment the orchestrator may hold;
 *       its {@code commandId} deduplication decides. The trade-off: when the first submit never arrived, the retry
 *       creates the payment on a reference that was valid at the first attempt, until the key expires.
 *   <li>{@code SUBMITTED}, another hash: {@code 422 IDEMPOTENCY_KEY_REUSED}.
 * </ul>
 *
 * No transaction spans the submit: each store call is a statement of its own, so a slow orchestrator holds no lock.
 * Records {@code tpe.api.idempotency{outcome=new|replay|reused|in_progress|resubmit}} (§7).
 */
public class IdempotencyGuard {

    /** Validation, the VoP reference check and the submit: what runs for a key claimed or resumed. */
    @FunctionalInterface
    public interface Submit {

        /**
         * @param checked the {@code vopResult} of an earlier submit under the key that may have arrived, to submit
         *     again without a reference check; {@code null} for a new key
         * @throws OrchestratorUnavailableException carrying the {@code vopResult} of a submit that may have arrived
         */
        PaymentAccepted run(VopResult checked);
    }

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
    private final Counter resubmitted;

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
        this.resubmitted = outcome(meters, "resubmit");
    }

    /**
     * @param submit what runs for a new or a resumed key
     * @throws IdempotencyKeyReusedException the key was used for another request
     * @throws IdempotencyKeyInProgressException a request with the key is being processed
     */
    public PaymentAccepted initiate(IdempotentRequest request, Submit submit) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Instant now = now();
            if (keys.claim(request, now, now.plus(retention))) {
                return run(request, now, null, submit);
            }
            Optional<IdempotencyKeys.Entry> found = keys.find(request.clientId(), request.key());
            if (found.isEmpty()) {
                continue;
            }
            IdempotencyKeys.Entry entry = found.get();
            if (entry.status() == IdempotencyKeys.Status.COMPLETED) {
                return replay(request, entry);
            }
            if (entry.status() == IdempotencyKeys.Status.SUBMITTED) {
                if (!entry.requestHash().equals(request.requestHash())) {
                    throw reused(request);
                }
                if (!keys.resume(request, entry.createdAt(), now)) {
                    continue;
                }
                LOG.info("resubmitting under an Idempotency-Key of client {} whose submit may have arrived at {}",
                        request.clientId(), entry.createdAt());
                resubmitted.increment();
                return run(request, now, entry.vopResult(), submit);
            }
            if (now.isBefore(entry.createdAt().plus(takeover))) {
                inProgress.increment();
                throw new IdempotencyKeyInProgressException("a request with the key is being processed");
            }
            // a resumed key abandoned mid-resubmit is still a SUBMITTED one: its body and result stay the key's
            if (entry.vopResult() != null && !entry.requestHash().equals(request.requestHash())) {
                throw reused(request);
            }
            if (!keys.takeOver(request, entry.createdAt(), now, now.plus(retention))) {
                inProgress.increment();
                throw new IdempotencyKeyInProgressException("a request with the key is being processed");
            }
            LOG.info("took over an abandoned Idempotency-Key of client {}, in progress since {}", request.clientId(),
                    entry.createdAt());
            return run(request, now, entry.vopResult(), submit);
        }
        throw new IllegalStateException("Idempotency-Key neither claimed nor found after " + MAX_ATTEMPTS
                + " attempts");
    }

    /** @param checked the stored {@code vopResult} of a resumed key; {@code null} for a claimed one */
    private PaymentAccepted run(IdempotentRequest request, Instant claimedAt, VopResult checked, Submit submit) {
        PaymentAccepted payment;
        try {
            payment = submit.run(checked);
        } catch (RuntimeException e) {
            VopResult submitted = e instanceof OrchestratorUnavailableException unavailable
                    && unavailable.submittedWith() != null ? unavailable.submittedWith() : checked;
            // a key whose submit may have arrived stays SUBMITTED whatever its retries answer: only a 202 settles it
            if (submitted == null) {
                keys.release(request, claimedAt);
            } else {
                keys.submitted(request, claimedAt, submitted);
            }
            throw e;
        }
        // a request whose key was taken over meanwhile stores nothing: the new holder's outcome is the key's
        keys.complete(request, claimedAt, payment);
        if (checked == null) {
            created.increment();
        }
        return payment;
    }

    private PaymentAccepted replay(IdempotentRequest request, IdempotencyKeys.Entry entry) {
        if (!entry.requestHash().equals(request.requestHash())) {
            throw reused(request);
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

    private IdempotencyKeyReusedException reused(IdempotentRequest request) {
        reused.increment();
        return new IdempotencyKeyReusedException("the Idempotency-Key of client " + request.clientId()
                + " is another request's");
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
