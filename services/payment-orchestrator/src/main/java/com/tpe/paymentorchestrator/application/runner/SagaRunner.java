package com.tpe.paymentorchestrator.application.runner;

import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentorchestrator.application.payments.ActionClaim;
import com.tpe.paymentorchestrator.application.payments.Payments;
import com.tpe.paymentorchestrator.application.runner.SagaTelemetry.Result;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentState;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The saga runner (specs/11-payment-orchestrator.md §6.3, ADR-008): performs a payment's pending action on any
 * instance. Claim with a conditional update (one runner wins), call outside any transaction, apply the outcome in a
 * second transaction while the payment is still as claimed. A technical failure retries after the back-off unless
 * the action gives up. Actions run on virtual threads, at most {@code max-concurrency} at once; a payment already
 * queued here is not queued again. The sweeper hands over what a crash or a retry left due.
 */
public class SagaRunner implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SagaRunner.class);

    private final Payments payments;
    private final StepApplier applier;
    private final Map<PendingAction, SagaAction> actions = new EnumMap<>(PendingAction.class);
    private final RunnerSettings settings;
    private final Clock clock;
    private final SagaTelemetry telemetry;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots;
    private final Set<PaymentId> queued = ConcurrentHashMap.newKeySet();

    public SagaRunner(Payments payments, StepApplier applier, List<SagaAction> actions, RunnerSettings settings,
            Clock clock, SagaTelemetry telemetry) {
        this.payments = payments;
        this.applier = applier;
        actions.forEach(action -> this.actions.put(action.action(), action));
        if (this.actions.isEmpty()) {
            throw new IllegalArgumentException("the runner needs at least one action");
        }
        this.settings = settings;
        this.clock = clock;
        this.telemetry = telemetry;
        this.slots = new Semaphore(settings.maxConcurrency());
    }

    /** The actions this runner performs; the sweeper looks for these only. */
    public Set<PendingAction> actions() {
        return Set.copyOf(actions.keySet());
    }

    /**
     * Runs the payment's pending action once the current transaction commits, or now without one.
     *
     * @param resumed handed over by the sweeper, not by the transaction that set the action
     */
    public void submitAfterCommit(PaymentId paymentId, boolean resumed) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit(paymentId, resumed);
                }
            });
        } else {
            submit(paymentId, resumed);
        }
    }

    private void submit(PaymentId paymentId, boolean resumed) {
        if (!queued.add(paymentId)) {
            // already waiting for a slot here; the claim would turn the second one away anyway
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    slots.acquire();
                } catch (InterruptedException e) {
                    // shutting down: the sweeper of this or another instance resumes it
                    queued.remove(paymentId);
                    Thread.currentThread().interrupt();
                    return;
                }
                queued.remove(paymentId);
                try {
                    run(paymentId, resumed);
                } catch (RuntimeException e) {
                    // the lease expires and the sweeper hands the action over again
                    LOG.error("payment {} action failed: {}", paymentId.value(), e.getClass().getSimpleName());
                } finally {
                    slots.release();
                }
            });
        } catch (RejectedExecutionException e) {
            queued.remove(paymentId);
            LOG.info("payment {} not run: the runner is shutting down; a sweeper resumes it", paymentId.value());
        }
    }

    /** Claims and performs the payment's pending action, if it is due and nobody else has it. */
    public void run(PaymentId paymentId, boolean resumed) {
        Instant now = clock.instant();
        Optional<ActionClaim> claimed = payments.claim(paymentId, actions.keySet(), now, now.plus(settings.lease()));
        if (claimed.isEmpty()) {
            return;
        }
        ActionClaim claim = claimed.get();
        Payment payment = payments.find(paymentId).orElseThrow();
        SagaAction action = actions.get(claim.action());
        SagaTelemetry.ActionSpan span = telemetry.actionStarted(payment, claim.action(), claim.attempts(), resumed);
        Result result = Result.ERROR;
        try {
            StepOutcome outcome = action.perform(payment);
            Result applied = Result.OK;
            if (outcome instanceof StepOutcome.TechnicalFailure failure) {
                LOG.warn("payment {} {} attempt {} failed: {}", paymentId.value(), claim.action(), claim.attempts(),
                        failure.detail());
                Optional<StepOutcome.Moved> giveUp = action.giveUp(payment, claim.attempts(), failure);
                if (giveUp.isEmpty()) {
                    Instant after = clock.instant();
                    applier.retry(claim, after.plus(settings.backoff(claim.attempts())), after);
                    result = Result.RETRY;
                    return;
                }
                outcome = giveUp.get();
                applied = Result.GAVE_UP;
            }
            result = apply(claim, (StepOutcome.Moved) outcome, span) ? applied : Result.DISCARDED;
        } finally {
            span.finished(result);
        }
    }

    private boolean apply(ActionClaim claim, StepOutcome.Moved moved, SagaTelemetry.ActionSpan span) {
        if (!applier.apply(claim, moved, clock.instant())) {
            return false;
        }
        Payment next = moved.next();
        if (next.state() == PaymentState.REJECTED) {
            span.rejected(next, moved.rejection());
            telemetry.rejected(next, moved.rejection());
        }
        if (next.state().isFinal() && next.pendingAction() == null) {
            telemetry.finalised(next);
        }
        if (next.pendingAction() != null && actions.containsKey(next.pendingAction())) {
            submit(next.paymentId(), false);
        }
        return true;
    }

    /**
     * Stops taking actions: queued ones are dropped for a sweeper, running ones are interrupted (their lease expires
     * and a sweeper resumes them; every remote call is idempotent).
     */
    @Override
    public void close() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(settings.safetyMargin().toMillis() + 1000, TimeUnit.MILLISECONDS)) {
                LOG.warn("saga actions still running at shutdown; their leases expire and a sweeper resumes them");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
