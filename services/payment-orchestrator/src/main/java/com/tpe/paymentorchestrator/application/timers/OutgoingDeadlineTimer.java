package com.tpe.paymentorchestrator.application.timers;

import com.tpe.paymentorchestrator.application.payments.DueTimer;
import com.tpe.paymentorchestrator.application.payments.FinalEvents;
import com.tpe.paymentorchestrator.application.payments.LockedPayment;
import com.tpe.paymentorchestrator.application.payments.Payments;
import com.tpe.paymentorchestrator.application.runner.SagaRunner;
import com.tpe.paymentorchestrator.application.runner.SagaTelemetry;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentState;
import com.tpe.paymentorchestrator.domain.payment.RejectionCause;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@code OUTGOING_DEADLINE} (specs/11-payment-orchestrator.md §6.4): no outgoing payment outlives the scheme deadline
 * before it reaches the CSM. Runs inside the timer poll's transaction and changes only local state: it locks the
 * payment, applies {@link Payment#atDeadline} unless a CSM outcome is recorded, and marks the timer {@code FIRED}.
 * The version it moves the payment to discards the result of any action still in flight (§6.6); a release it sets
 * runs after the commit.
 */
public class OutgoingDeadlineTimer {

    /** {@code payment_transitions.cause}. */
    static final String DEADLINE = "DEADLINE";

    private static final Logger LOG = LoggerFactory.getLogger(OutgoingDeadlineTimer.class);

    private final Payments payments;
    private final FinalEvents finalEvents;
    private final SagaRunner runner;
    private final SagaTelemetry telemetry;
    private final Clock clock;

    public OutgoingDeadlineTimer(Payments payments, FinalEvents finalEvents, SagaRunner runner,
            SagaTelemetry telemetry, Clock clock) {
        this.payments = payments;
        this.finalEvents = finalEvents;
        this.runner = runner;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    /** Must run in the transaction that locked {@code timer}'s row. */
    public void fire(DueTimer timer) {
        Instant now = clock.instant();
        Optional<LockedPayment> locked = payments.lockForTimer(timer.paymentId());
        if (locked.isPresent()) {
            SagaTelemetry.TimerSpan span = telemetry.timerStarted(locked.get().payment(), timer.type(),
                    Duration.between(timer.dueAt(), now));
            try {
                apply(locked.get(), now);
            } finally {
                span.finished();
            }
        }
        payments.markTimerFired(timer.timerId());
        afterCommit(() -> telemetry.timerFired(timer.type()));
    }

    private void apply(LockedPayment locked, Instant now) {
        Payment payment = locked.payment();
        Optional<Payment> moved = locked.csmOutcomeRecorded() ? Optional.empty() : payment.atDeadline(now);
        if (moved.isEmpty()) {
            if (payment.state() == PaymentState.SENT_TO_CSM || payment.state() == PaymentState.INVESTIGATING) {
                // the investigation of a payment the CSM has comes with M3
                LOG.warn("payment {} deadline in {}: no investigation yet", payment.paymentId().value(),
                        payment.state());
            }
            return;
        }
        Payment next = moved.get();
        boolean finalEvent = next.pendingAction() == null;
        payments.applyTimer(locked, next, finalEvent, now);
        payments.recordTransition(next.paymentId(), payment.state(), next.state(), next.reasonCode(), DEADLINE, now);
        if (finalEvent) {
            finalEvents.emit(next);
        }
        // one line per state transition (specs/26-observability.md §4.3); no parties or amounts
        LOG.info("payment {} txId {} {} -> {} ({}) {}", next.paymentId().value(), next.txId().value(), payment.state(),
                next.state(), DEADLINE, next.reasonCode().code());
        afterCommit(() -> {
            telemetry.rejected(next, RejectionCause.TECHNICAL);
            telemetry.deadlineRejected(next);
            if (finalEvent) {
                telemetry.finalised(next);
            }
        });
        if (next.pendingAction() != null) {
            runner.submitAfterCommit(next.paymentId(), false);
        }
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
