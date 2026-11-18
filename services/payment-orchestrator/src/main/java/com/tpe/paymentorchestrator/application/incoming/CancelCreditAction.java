package com.tpe.paymentorchestrator.application.incoming;

import com.tpe.paymentorchestrator.application.ledger.Ledger;
import com.tpe.paymentorchestrator.application.ledger.LedgerAnswer;
import com.tpe.paymentorchestrator.application.ledger.LedgerRefusal;
import com.tpe.paymentorchestrator.application.runner.RunnerSettings;
import com.tpe.paymentorchestrator.application.runner.SagaAction;
import com.tpe.paymentorchestrator.application.runner.StepOutcome;
import com.tpe.paymentorchestrator.domain.payment.ExceptionCaseType;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * {@code CANCEL_CREDIT} of a rejected incoming payment (specs/11-payment-orchestrator.md §6.2, ADR-009): the ledger
 * removes the pending credit, or tombstones one that never came, and {@code PaymentRejected} follows in the same
 * transaction. {@code PENDING_CREDIT_CONFIRMED} means the money was booked to the payee although the payment was
 * rejected: a {@code CONTRADICTING_CSM_OUTCOME} case, no final event. Anything else is retried forever, with a
 * {@code LEDGER_ACTION_STUCK} case after {@code stuck-after}.
 */
public class CancelCreditAction implements SagaAction {

    private final Ledger ledger;
    private final RunnerSettings settings;
    private final Duration timeout;
    private final Clock clock;

    /** @param timeout {@code tpe.clients.ledger.timeout}, 1 s */
    public CancelCreditAction(Ledger ledger, RunnerSettings settings, Duration timeout, Clock clock) {
        this.ledger = ledger;
        this.settings = settings;
        this.timeout = timeout;
        this.clock = clock;
    }

    @Override
    public PendingAction action() {
        return PendingAction.CANCEL_CREDIT;
    }

    @Override
    public StepOutcome perform(Payment payment) {
        // a compensation has no deadline
        Duration readTimeout = settings.readTimeout(timeout, null, clock.instant()).orElseThrow();
        return switch (ledger.cancelPendingCredit(payment, readTimeout)) {
            case LedgerAnswer.Done<Ledger.Cancelled> done ->
                    new StepOutcome.Moved(payment.compensated(), "PENDING_CREDIT_CANCELLED", null);
            case LedgerAnswer.Refused<Ledger.Cancelled> refused
                    when LedgerRefusal.PENDING_CREDIT_CONFIRMED.equals(refused.refusal().code()) ->
                    new StepOutcome.Moved(payment.compensated(), "PENDING_CREDIT_CONFIRMED", null,
                            ExceptionCaseType.CONTRADICTING_CSM_OUTCOME);
            case LedgerAnswer.Refused<Ledger.Cancelled> refused -> new StepOutcome.TechnicalFailure(
                    "ledger-service refused the cancel: " + refused.refusal().code(), false);
            case LedgerAnswer.Unavailable<Ledger.Cancelled> unavailable ->
                    new StepOutcome.TechnicalFailure(unavailable.detail(), unavailable.retryable());
        };
    }

    /** Never: a rejected payment's pending credit must go (§6.2). */
    @Override
    public Optional<StepOutcome.Moved> giveUp(Payment payment, int attempts, StepOutcome.TechnicalFailure failure) {
        return Optional.empty();
    }

    @Override
    public boolean opensStuckCase() {
        return true;
    }
}
