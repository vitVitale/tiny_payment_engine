package com.tpe.paymentapi.application.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.contracts.Direction;
import com.tpe.contracts.payment.PaymentAccepted;
import com.tpe.contracts.payment.PaymentState;
import com.tpe.contracts.payment.PaymentView;
import com.tpe.contracts.payment.SubmitPayment;
import com.tpe.contracts.vop.VopResult;
import com.tpe.paymentapi.application.status.PaymentStatuses;
import com.tpe.paymentapi.application.submission.Failure;
import com.tpe.paymentapi.application.submission.IdempotencyKeyReusedException;
import com.tpe.paymentapi.application.submission.Orchestrator;
import com.tpe.paymentapi.application.submission.OrchestratorUnavailableException;
import com.tpe.paymentapi.application.submission.UnexpectedAnswerException;
import com.tpe.paymentapi.application.validation.ValidationFailedException;
import com.tpe.paymentdomain.MutableClock;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.TxId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** specs/10-payment-api.md §5.1, UC-01 2a, 2b, 5a, 6a: the idempotency guard against an in-memory key store. */
class IdempotencyGuardTest {

    private static final Instant START = Instant.parse("2026-11-14T10:00:00.123Z");
    private static final IdempotentRequest REQUEST = new IdempotentRequest("mobile-app", "key-1", "hash-1");
    private static final IdempotentRequest OTHER_BODY = new IdempotentRequest("mobile-app", "key-1", "hash-2");
    private static final PaymentAccepted ACCEPTED = new PaymentAccepted(
            new PaymentId(UUID.fromString("0192f0e4-7b1c-7cc3-a1b2-000000000001")), PaymentState.RECEIVED,
            new TxId("TPE01JABCDEFGHJKMNPQRST0001"), START);

    private final MutableClock clock = new MutableClock(START);
    private final MemoryKeys keys = new MemoryKeys();
    private final StatusOrchestrator orchestrator = new StatusOrchestrator();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final IdempotencyGuard guard = new IdempotencyGuard(keys, new PaymentStatuses(orchestrator), clock,
            Duration.ofHours(24), Duration.ofSeconds(10), meters);
    private final AtomicInteger submits = new AtomicInteger();
    /** Per submit: whether it checked the reference or was given the stored result. */
    private final List<String> checks = new ArrayList<>();

    @Test
    void aNewKeyRunsTheSubmitAndStoresItsPayment() {
        assertThat(guard.initiate(REQUEST, this::submit)).isEqualTo(ACCEPTED);

        assertThat(submits).hasValue(1);
        assertThat(keys.rows.get("key-1")).isEqualTo(new IdempotencyKeys.Entry("hash-1",
                IdempotencyKeys.Status.COMPLETED, ACCEPTED, START, START.plus(Duration.ofHours(24))));
        assertThat(outcome("new")).isEqualTo(1);
    }

    /** UC-01 2a: the stored payment, its state as the orchestrator has it now. */
    @Test
    void theSameRequestAgainReplaysThePaymentWithItsCurrentState() {
        guard.initiate(REQUEST, this::submit);
        orchestrator.reads.add(found(PaymentState.SETTLED));

        PaymentAccepted replayed = guard.initiate(REQUEST, this::submit);

        assertThat(submits).hasValue(1);
        assertThat(replayed).isEqualTo(new PaymentAccepted(ACCEPTED.paymentId(), PaymentState.SETTLED,
                ACCEPTED.txId(), ACCEPTED.acceptedAt()));
        assertThat(orchestrator.asked).containsExactly(ACCEPTED.paymentId());
        assertThat(outcome("replay")).isEqualTo(1);
    }

    @Test
    void aStatusReadThatFailsTwiceReplaysTheStoredPayment() {
        guard.initiate(REQUEST, this::submit);
        orchestrator.reads.add(new Orchestrator.Unavailable(Failure.TIMEOUT, "slow"));
        orchestrator.reads.add(new Orchestrator.Unavailable(Failure.CONNECT, "down"));

        assertThat(guard.initiate(REQUEST, this::submit)).isEqualTo(ACCEPTED);
        assertThat(orchestrator.asked).hasSize(2);
    }

    @Test
    void aStatusReadIsRetriedOnce() {
        guard.initiate(REQUEST, this::submit);
        orchestrator.reads.add(new Orchestrator.Unavailable(Failure.GATEWAY, "503"));
        orchestrator.reads.add(found(PaymentState.SENT_TO_CSM));

        assertThat(guard.initiate(REQUEST, this::submit).state()).isEqualTo(PaymentState.SENT_TO_CSM);
    }

    @Test
    void aStatusReadAnsweredOutsideTheContractReplaysTheStoredPayment() {
        guard.initiate(REQUEST, this::submit);
        orchestrator.unexpected = true;

        assertThat(guard.initiate(REQUEST, this::submit)).isEqualTo(ACCEPTED);
    }

    @Test
    void aStatusReadThatFindsNoPaymentReplaysTheStoredPayment() {
        guard.initiate(REQUEST, this::submit);
        orchestrator.reads.add(new Orchestrator.NotFound());

        assertThat(guard.initiate(REQUEST, this::submit)).isEqualTo(ACCEPTED);
    }

    /** UC-01 2b. */
    @Test
    void anotherRequestUnderACompletedKeyIsReused() {
        guard.initiate(REQUEST, this::submit);

        assertThatThrownBy(() -> guard.initiate(OTHER_BODY, this::submit))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(submits).hasValue(1);
        assertThat(orchestrator.asked).isEmpty();
        assertThat(outcome("reused")).isEqualTo(1);
    }

    @Test
    void aKeyInProgressIsRefusedWhileItIsYoung() {
        keys.claim(REQUEST, START, START.plus(Duration.ofHours(24)));
        clock.advance(Duration.ofSeconds(9));

        assertThatThrownBy(() -> guard.initiate(REQUEST, this::submit))
                .isInstanceOf(IdempotencyKeyInProgressException.class);
        assertThat(submits).hasValue(0);
        assertThat(keys.rows.get("key-1").status()).isEqualTo(IdempotencyKeys.Status.IN_PROGRESS);
        assertThat(outcome("in_progress")).isEqualTo(1);
    }

    /** Safe: the orchestrator deduplicates by {@code commandId}. */
    @Test
    void anAbandonedKeyInProgressIsTakenOver() {
        keys.claim(OTHER_BODY, START, START.plus(Duration.ofHours(24)));
        clock.advance(Duration.ofSeconds(10));
        Instant now = clock.instant();

        assertThat(guard.initiate(REQUEST, this::submit)).isEqualTo(ACCEPTED);

        assertThat(submits).hasValue(1);
        assertThat(keys.rows.get("key-1")).isEqualTo(new IdempotencyKeys.Entry("hash-1",
                IdempotencyKeys.Status.COMPLETED, ACCEPTED, now, now.plus(Duration.ofHours(24))));
        assertThat(outcome("new")).isEqualTo(1);
    }

    @Test
    void aTakeOverLostToAnotherRequestIsInProgress() {
        keys.claim(REQUEST, START, START.plus(Duration.ofHours(24)));
        clock.advance(Duration.ofSeconds(11));
        keys.beforeTakeOver = () -> keys.takeOver(REQUEST, START, clock.instant(), clock.instant());

        assertThatThrownBy(() -> guard.initiate(REQUEST, this::submit))
                .isInstanceOf(IdempotencyKeyInProgressException.class);
        assertThat(submits).hasValue(0);
    }

    /**
     * {@code 400}, {@code 422}, a {@code 503} whose submit did not arrive: no side effect, the client may fix the
     * request and retry.
     */
    @Test
    void aFailedSubmitReleasesTheKey() {
        assertThatThrownBy(() -> guard.initiate(REQUEST, checked -> {
            throw new OrchestratorUnavailableException("down");
        })).isInstanceOf(OrchestratorUnavailableException.class);
        assertThat(keys.rows).isEmpty();

        assertThatThrownBy(() -> guard.initiate(OTHER_BODY, checked -> {
            throw new ValidationFailedException(List.of());
        })).isInstanceOf(ValidationFailedException.class);
        assertThat(keys.rows).isEmpty();

        assertThat(guard.initiate(REQUEST, this::submit)).isEqualTo(ACCEPTED);
    }

    /** UC-01 6a: a timeout or a {@code 5xx} may have created the payment; the key keeps the check's result. */
    @Test
    void aSubmitThatMayHaveArrivedLeavesTheKeySubmittedWithItsVopResult() {
        submitTimedOut();

        assertThat(keys.rows.get("key-1")).isEqualTo(new IdempotencyKeys.Entry("hash-1",
                IdempotencyKeys.Status.SUBMITTED, null, START, START.plus(Duration.ofHours(24)),
                VopResult.CLOSE_MATCH));
    }

    /** The reference may have expired since: the retry submits the stored result, the orchestrator deduplicates. */
    @Test
    void aRetryOfASubmittedKeySubmitsAgainWithTheStoredVopResult() {
        submitTimedOut();
        clock.advance(Duration.ofMinutes(31));
        Instant now = clock.instant();

        assertThat(guard.initiate(REQUEST, this::submit)).isEqualTo(ACCEPTED);

        assertThat(checks).containsExactly("stored CLOSE_MATCH");
        assertThat(keys.rows.get("key-1")).isEqualTo(new IdempotencyKeys.Entry("hash-1",
                IdempotencyKeys.Status.COMPLETED, ACCEPTED, now, START.plus(Duration.ofHours(24)),
                VopResult.CLOSE_MATCH));
        assertThat(outcome("resubmit")).isEqualTo(1);
        assertThat(outcome("new")).isZero();
    }

    /** Only a {@code 202} settles a key whose submit may have arrived; any failed retry leaves it so. */
    @Test
    void aSubmittedKeyStaysSubmittedWhenItsRetryFails() {
        submitTimedOut();

        assertThatThrownBy(() -> guard.initiate(REQUEST, checked -> {
            throw new OrchestratorUnavailableException("connect");
        })).isInstanceOf(OrchestratorUnavailableException.class);
        assertThat(keys.rows.get("key-1").status()).isEqualTo(IdempotencyKeys.Status.SUBMITTED);
        assertThatThrownBy(() -> guard.initiate(REQUEST, checked -> {
            throw new ValidationFailedException(List.of());
        })).isInstanceOf(ValidationFailedException.class);
        assertThat(keys.rows.get("key-1").status()).isEqualTo(IdempotencyKeys.Status.SUBMITTED);

        guard.initiate(REQUEST, this::submit);
        assertThat(checks).containsExactly("stored CLOSE_MATCH");
    }

    @Test
    void aSubmittedKeyWithAnotherBodyIsReused() {
        submitTimedOut();

        assertThatThrownBy(() -> guard.initiate(OTHER_BODY, this::submit))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(submits).hasValue(0);
        assertThat(keys.rows.get("key-1").status()).isEqualTo(IdempotencyKeys.Status.SUBMITTED);
        assertThat(outcome("reused")).isEqualTo(1);
    }

    /** A retry that crashed while it resubmitted leaves the key in progress, with the stored result. */
    @Test
    void aResumedKeyAbandonedMidResubmitIsTakenOverWithItsStoredResult() {
        submitTimedOut();
        keys.resume(REQUEST, START, START);
        clock.advance(Duration.ofSeconds(10));

        assertThatThrownBy(() -> guard.initiate(OTHER_BODY, this::submit))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(guard.initiate(REQUEST, this::submit)).isEqualTo(ACCEPTED);
        assertThat(checks).containsExactly("stored CLOSE_MATCH");
    }

    @Test
    void aResumeLostToAnotherRetryIsInProgress() {
        submitTimedOut();
        keys.beforeResume = () -> keys.resume(REQUEST, START, clock.instant());

        assertThatThrownBy(() -> guard.initiate(REQUEST, this::submit))
                .isInstanceOf(IdempotencyKeyInProgressException.class);
        assertThat(submits).hasValue(0);
    }

    /** A request whose key was taken over while it ran leaves the new owner's row alone. */
    @Test
    void aFailedSubmitDoesNotReleaseAKeyTakenOverMeanwhile() {
        assertThatThrownBy(() -> guard.initiate(REQUEST, checked -> {
            clock.advance(Duration.ofSeconds(30));
            keys.takeOver(OTHER_BODY, START, clock.instant(), clock.instant().plus(Duration.ofHours(24)));
            throw new OrchestratorUnavailableException("down");
        })).isInstanceOf(OrchestratorUnavailableException.class);

        assertThat(keys.rows.get("key-1").requestHash()).isEqualTo("hash-2");
    }

    @Test
    void aKeyReleasedBetweenTheClaimAndTheReadIsClaimedAgain() {
        keys.claim(OTHER_BODY, START, START.plus(Duration.ofHours(24)));
        keys.beforeFind = () -> keys.rows.clear();

        assertThat(guard.initiate(REQUEST, this::submit)).isEqualTo(ACCEPTED);
        assertThat(submits).hasValue(1);
    }

    @Test
    void retentionDeletesTheKeysPastTheirExpiry() {
        var retention = new IdempotencyRetention(keys, clock);
        guard.initiate(REQUEST, this::submit);
        clock.advance(Duration.ofHours(24).minusMillis(1));
        assertThat(retention.purge()).isZero();

        clock.advance(Duration.ofMillis(2));
        assertThat(retention.purge()).isEqualTo(1);
        assertThat(keys.rows).isEmpty();
    }

    /** The slow request's payment is not stored under the hash of the request that took its key over. */
    @Test
    void aSubmitWhoseKeyWasTakenOverMeanwhileStoresNothing() {
        assertThat(guard.initiate(REQUEST, checked -> {
            clock.advance(Duration.ofSeconds(30));
            keys.takeOver(OTHER_BODY, START, clock.instant(), clock.instant().plus(Duration.ofHours(24)));
            return submit(checked);
        })).isEqualTo(ACCEPTED);

        assertThat(keys.rows.get("key-1").requestHash()).isEqualTo("hash-2");
        assertThat(keys.rows.get("key-1").status()).isEqualTo(IdempotencyKeys.Status.IN_PROGRESS);
    }

    private static Orchestrator.Found found(PaymentState state) {
        return new Orchestrator.Found(new PaymentView(ACCEPTED.paymentId(), Direction.OUTGOING, false, state, null,
                ACCEPTED.txId(), null, null, null, null, null, null, ACCEPTED.acceptedAt(), null));
    }

    private PaymentAccepted submit(VopResult checked) {
        submits.incrementAndGet();
        checks.add(checked == null ? "checked" : "stored " + checked);
        return ACCEPTED;
    }

    /** The first attempt's submit timed out: the key is {@code SUBMITTED} with {@code CLOSE_MATCH}. */
    private void submitTimedOut() {
        assertThatThrownBy(() -> guard.initiate(REQUEST, checked -> {
            throw new OrchestratorUnavailableException("timeout", VopResult.CLOSE_MATCH);
        })).isInstanceOf(OrchestratorUnavailableException.class);
    }

    private double outcome(String outcome) {
        var counter = meters.find("tpe.api.idempotency").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    /** The semantics of {@code JdbcIdempotencyKeys}, one client. */
    private static final class MemoryKeys implements IdempotencyKeys {

        final Map<String, Entry> rows = new HashMap<>();
        Runnable beforeFind = () -> {};
        Runnable beforeTakeOver = () -> {};
        Runnable beforeResume = () -> {};

        @Override
        public boolean claim(IdempotentRequest request, Instant createdAt, Instant expiresAt) {
            return rows.putIfAbsent(request.key(),
                    new Entry(request.requestHash(), Status.IN_PROGRESS, null, createdAt, expiresAt)) == null;
        }

        @Override
        public Optional<Entry> find(String clientId, String key) {
            Runnable hook = beforeFind;
            beforeFind = () -> {};
            hook.run();
            return Optional.ofNullable(rows.get(key));
        }

        @Override
        public boolean takeOver(IdempotentRequest request, Instant abandonedAt, Instant createdAt,
                Instant expiresAt) {
            Runnable hook = beforeTakeOver;
            beforeTakeOver = () -> {};
            hook.run();
            Entry row = rows.get(request.key());
            if (row == null || row.status() != Status.IN_PROGRESS || !row.createdAt().equals(abandonedAt)) {
                return false;
            }
            rows.put(request.key(), new Entry(request.requestHash(), Status.IN_PROGRESS, null, createdAt, expiresAt,
                    row.vopResult()));
            return true;
        }

        @Override
        public void complete(IdempotentRequest request, Instant createdAt, PaymentAccepted payment) {
            Entry row = rows.get(request.key());
            if (row == null || row.status() != Status.IN_PROGRESS || !row.createdAt().equals(createdAt)) {
                return;
            }
            rows.put(request.key(), new Entry(row.requestHash(), Status.COMPLETED, payment, row.createdAt(),
                    row.expiresAt(), row.vopResult()));
        }

        @Override
        public void release(IdempotentRequest request, Instant createdAt) {
            Entry row = rows.get(request.key());
            if (row != null && row.status() == Status.IN_PROGRESS && row.createdAt().equals(createdAt)) {
                rows.remove(request.key());
            }
        }

        @Override
        public void submitted(IdempotentRequest request, Instant createdAt, VopResult vopResult) {
            Entry row = rows.get(request.key());
            if (row != null && row.status() == Status.IN_PROGRESS && row.createdAt().equals(createdAt)) {
                rows.put(request.key(), new Entry(row.requestHash(), Status.SUBMITTED, null, row.createdAt(),
                        row.expiresAt(), vopResult));
            }
        }

        @Override
        public boolean resume(IdempotentRequest request, Instant submittedAt, Instant createdAt) {
            Runnable hook = beforeResume;
            beforeResume = () -> {};
            hook.run();
            Entry row = rows.get(request.key());
            if (row == null || row.status() != Status.SUBMITTED || !row.createdAt().equals(submittedAt)
                    || !row.requestHash().equals(request.requestHash())) {
                return false;
            }
            rows.put(request.key(), new Entry(row.requestHash(), Status.IN_PROGRESS, null, createdAt,
                    row.expiresAt(), row.vopResult()));
            return true;
        }

        @Override
        public int deleteExpired(Instant now, int limit) {
            List<String> expired = new ArrayList<>();
            rows.forEach((key, row) -> {
                if (row.expiresAt().isBefore(now) && expired.size() < limit) {
                    expired.add(key);
                }
            });
            expired.forEach(rows::remove);
            return expired.size();
        }
    }

    private static final class StatusOrchestrator implements Orchestrator {

        final Deque<Read> reads = new ArrayDeque<>();
        final List<PaymentId> asked = new ArrayList<>();
        boolean unexpected;

        @Override
        public Answer submit(SubmitPayment command) {
            throw new AssertionError("the guard never submits");
        }

        @Override
        public Read payment(PaymentId paymentId) {
            asked.add(paymentId);
            if (unexpected) {
                throw new UnexpectedAnswerException("400");
            }
            return reads.isEmpty() ? new Orchestrator.Unavailable(Failure.CONNECT, "no answer") : reads.removeFirst();
        }
    }
}
