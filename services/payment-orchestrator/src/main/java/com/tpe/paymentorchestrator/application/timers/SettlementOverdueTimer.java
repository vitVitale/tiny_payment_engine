package com.tpe.paymentorchestrator.application.timers;

import com.tpe.paymentorchestrator.application.payments.DueTimer;
import com.tpe.paymentorchestrator.application.payments.LockedPayment;
import com.tpe.paymentorchestrator.application.payments.Payments;
import com.tpe.paymentorchestrator.application.runner.SagaTelemetry;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * {@code SETTLEMENT_OVERDUE} (specs/11-payment-orchestrator.md §6.4, UC-06 8b): an accepted incoming payment whose
 * settlement does not arrive within {@code tpe.incoming.settlement-overdue} of the accepting answer goes into
 * investigation instead of waiting forever. Runs inside the timer poll's transaction and changes only local state: it
 * locks the payment and, if it is still {@code ACCEPTED} without a CSM outcome, makes it {@code INVESTIGATING} and
 * sends the first status request ({@link InvestigationTimer#start}); otherwise nothing. The timer is marked
 * {@code FIRED}.
 */
public class SettlementOverdueTimer {

    /** {@code payment_transitions.cause}. */
    static final String SETTLEMENT_OVERDUE = "SETTLEMENT_OVERDUE";

    private final Payments payments;
    private final InvestigationTimer investigation;
    private final SagaTelemetry telemetry;
    private final Clock clock;

    public SettlementOverdueTimer(Payments payments, InvestigationTimer investigation, SagaTelemetry telemetry,
            Clock clock) {
        this.payments = payments;
        this.investigation = investigation;
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
        Timers.afterCommit(() -> telemetry.timerFired(timer.type()));
    }

    private void apply(LockedPayment locked, Instant now) {
        // an outcome recorded cancels the timer: this one fired first under a stale read
        Optional<Payment> investigating = locked.csmOutcomeRecorded() ? Optional.empty()
                : locked.payment().settlementOverdue();
        investigating.ifPresent(next -> investigation.start(locked, next, SETTLEMENT_OVERDUE, now));
    }
}
