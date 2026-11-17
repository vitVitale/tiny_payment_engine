package com.tpe.paymentorchestrator.application.timers;

import com.tpe.contracts.Direction;
import com.tpe.contracts.RequestPaymentStatus;
import com.tpe.messaging.outbox.OutboxWriter;
import com.tpe.paymentorchestrator.application.payments.DueTimer;
import com.tpe.paymentorchestrator.application.payments.LockedPayment;
import com.tpe.paymentorchestrator.application.payments.Payments;
import com.tpe.paymentorchestrator.application.runner.SagaTelemetry;
import com.tpe.paymentorchestrator.application.runner.StepApplier;
import com.tpe.paymentorchestrator.domain.payment.ExceptionCaseType;
import com.tpe.paymentorchestrator.domain.payment.InvestigationPolicy;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentState;
import com.tpe.paymentorchestrator.domain.payment.TimerType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The status requests of an investigation (specs/11-payment-orchestrator.md §6.4, UC-05): each writes
 * {@code RequestPaymentStatus} with the payment's next {@code attempt} to the outbox and schedules
 * {@code INVESTIGATION_RETRY} per {@link InvestigationPolicy}. When the retry fires on a payment still
 * {@code INVESTIGATING} without a CSM outcome, the next request goes out, or, past the budget, an
 * {@code UNRESOLVED_INVESTIGATION} case opens and nothing more is asked automatically. The hold is never released
 * here: the CSM may have settled.
 */
public class InvestigationTimer {

    private static final Logger LOG = LoggerFactory.getLogger(InvestigationTimer.class);

    private final Payments payments;
    private final OutboxWriter outbox;
    private final InvestigationPolicy policy;
    private final SagaTelemetry telemetry;
    private final Clock clock;

    public InvestigationTimer(Payments payments, OutboxWriter outbox, InvestigationPolicy policy,
            SagaTelemetry telemetry, Clock clock) {
        this.payments = payments;
        this.outbox = outbox;
        this.policy = policy;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    /**
     * Puts the locked payment into investigation (UC-05 1): stores it {@code INVESTIGATING} with
     * {@code investigation_started_at = now}, records the transition and sends the first status request. Must run in
     * the transaction that locked the payment.
     *
     * @param investigating what the timer made of the payment, {@code INVESTIGATING}
     * @param cause the transition's {@code cause}
     */
    public void start(LockedPayment locked, Payment investigating, String cause, Instant now) {
        PaymentState from = locked.payment().state();
        payments.startInvestigation(locked, investigating, now);
        payments.recordTransition(investigating.paymentId(), from, investigating.state(), null, cause, now);
        // one line per state transition (specs/26-observability.md §4.3); no parties or amounts
        LOG.info("payment {} txId {} {} -> {} ({})", investigating.paymentId().value(),
                investigating.txId().value(), from, investigating.state(), cause);
        requestStatus(investigating, 1, now);
    }

    /** {@code INVESTIGATION_RETRY}; must run in the transaction that locked {@code timer}'s row. */
    public void fire(DueTimer timer) {
        Instant now = clock.instant();
        Optional<LockedPayment> locked = payments.lockForTimer(timer.paymentId());
        if (locked.isPresent()) {
            SagaTelemetry.TimerSpan span = telemetry.timerStarted(locked.get().payment(), timer.type(),
                    Duration.between(timer.dueAt(), now));
            try {
                retry(locked.get(), timer.attempt(), now);
            } finally {
                span.finished();
            }
        }
        payments.markTimerFired(timer.timerId());
        Timers.afterCommit(() -> telemetry.timerFired(timer.type()));
    }

    /**
     * @param requests the status requests of this investigation so far ({@link DueTimer#attempt}); not the payment's
     *     {@code status_request_attempt}, which counts across investigations
     */
    private void retry(LockedPayment locked, int requests, Instant now) {
        Payment payment = locked.payment();
        if (payment.state() != PaymentState.INVESTIGATING || locked.csmOutcomeRecorded()) {
            // the CSM answered and recording it cancels the timers; this one fired first under a stale read
            return;
        }
        if (policy.exhausted(locked.investigationStartedAt(), now)) {
            if (payments.openCase(payment.paymentId(), ExceptionCaseType.UNRESOLVED_INVESTIGATION, Map.of(
                    "investigationStartedAt", locked.investigationStartedAt().toString(),
                    "statusRequests", requests), now)) {
                LOG.warn("payment {} txId {} unresolved after {} status requests: UNRESOLVED_INVESTIGATION case "
                        + "opened", payment.paymentId().value(), payment.txId().value(), requests);
            }
            return;
        }
        requestStatus(payment, requests + 1, now);
    }

    /** @param request the number of this request within the investigation, from 1 */
    private void requestStatus(Payment payment, int request, Instant now) {
        int attempt = payments.nextStatusRequestAttempt(payment.paymentId(), now);
        // in the payment's trace: the timer's span is current
        outbox.write(StepApplier.CSM_COMMANDS, payment.txId().value(), new RequestPaymentStatus(payment.paymentId(),
                payment.txId(), payment.debtor().agentBic(), Direction.valueOf(payment.direction().name()), attempt,
                now));
        payments.scheduleTimer(payment.paymentId(), TimerType.INVESTIGATION_RETRY,
                now.plus(policy.delayAfter(request)), request, now);
        LOG.info("payment {} txId {} status request attempt {}", payment.paymentId().value(), payment.txId().value(),
                attempt);
    }
}
