package com.tpe.paymentorchestrator.application.outgoing;

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
 * {@code RELEASE_HOLD} of a rejected outgoing payment (specs/11-payment-orchestrator.md §6.2, ADR-009): the ledger
 * frees the payer's funds, or tombstones a hold that never came, and {@code PaymentRejected} follows in the same
 * transaction. {@code HOLD_SETTLED} means the money left although the payment was rejected: a
 * {@code CONTRADICTING_CSM_OUTCOME} case, no release and no final event. Anything else is retried forever, with a
 * {@code LEDGER_ACTION_STUCK} case after {@code stuck-after}.
 */
public class ReleaseHoldAction implements SagaAction {

    private final Ledger ledger;
    private final RunnerSettings settings;
    private final Duration timeout;
    private final Clock clock;

    /** @param timeout {@code tpe.clients.ledger.timeout}, 1 s */
    public ReleaseHoldAction(Ledger ledger, RunnerSettings settings, Duration timeout, Clock clock) {
        this.ledger = ledger;
        this.settings = settings;
        this.timeout = timeout;
        this.clock = clock;
    }

    @Override
    public PendingAction action() {
        return PendingAction.RELEASE_HOLD;
    }

    @Override
    public StepOutcome perform(Payment payment) {
        // a compensation has no deadline
        Duration readTimeout = settings.readTimeout(timeout, null, clock.instant()).orElseThrow();
        return switch (ledger.releaseHold(payment, readTimeout)) {
            case LedgerAnswer.Done<Ledger.Released> done ->
                    new StepOutcome.Moved(payment.compensated(), "HOLD_RELEASED", null);
            case LedgerAnswer.Refused<Ledger.Released> refused
                    when LedgerRefusal.HOLD_SETTLED.equals(refused.refusal().code()) -> new StepOutcome.Moved(
                    payment.compensated(), "HOLD_SETTLED", null, ExceptionCaseType.CONTRADICTING_CSM_OUTCOME);
            case LedgerAnswer.Refused<Ledger.Released> refused -> new StepOutcome.TechnicalFailure(
                    "ledger-service refused the release: " + refused.refusal().code(), false);
            case LedgerAnswer.Unavailable<Ledger.Released> unavailable ->
                    new StepOutcome.TechnicalFailure(unavailable.detail(), unavailable.retryable());
        };
    }

    /** Never: a held payer's funds must come free (§6.2). */
    @Override
    public Optional<StepOutcome.Moved> giveUp(Payment payment, int attempts, StepOutcome.TechnicalFailure failure) {
        return Optional.empty();
    }

    @Override
    public boolean opensStuckCase() {
        return true;
    }
}
