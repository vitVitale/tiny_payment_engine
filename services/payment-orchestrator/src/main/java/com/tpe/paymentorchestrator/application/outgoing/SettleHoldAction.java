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
 * {@code SETTLE_HOLD} of a payment the CSM settled (specs/11-payment-orchestrator.md §6.2, UC-04 8): the ledger books
 * the hold Dr payer / Cr the CSM settlement account, and the payment is {@code SETTLED} with {@code PaymentSettled}
 * in the same transaction. {@code HOLD_RELEASED} means the payer's funds were freed although the CSM moved the money:
 * a {@code CONTRADICTING_CSM_OUTCOME} case and no final event. Anything else is retried forever, with a
 * {@code LEDGER_ACTION_STUCK} case after {@code stuck-after}.
 */
public class SettleHoldAction implements SagaAction {

    private final Ledger ledger;
    private final RunnerSettings settings;
    private final Duration timeout;
    private final Clock clock;

    /** @param timeout {@code tpe.clients.ledger.timeout}, 1 s */
    public SettleHoldAction(Ledger ledger, RunnerSettings settings, Duration timeout, Clock clock) {
        this.ledger = ledger;
        this.settings = settings;
        this.timeout = timeout;
        this.clock = clock;
    }

    @Override
    public PendingAction action() {
        return PendingAction.SETTLE_HOLD;
    }

    @Override
    public StepOutcome perform(Payment payment) {
        // the CSM settled: no deadline is left
        Duration readTimeout = settings.readTimeout(timeout, null, clock.instant()).orElseThrow();
        return switch (ledger.settleHold(payment, readTimeout)) {
            case LedgerAnswer.Done<Ledger.HoldSettled> done -> new StepOutcome.Moved(
                    payment.settledFromHold(done.value().journalEntryId(), clock.instant()), "HOLD_SETTLED", null);
            case LedgerAnswer.Refused<Ledger.HoldSettled> refused
                    when LedgerRefusal.HOLD_RELEASED.equals(refused.refusal().code()) -> new StepOutcome.Moved(
                    payment.settleAbandoned(), "HOLD_RELEASED", null, ExceptionCaseType.CONTRADICTING_CSM_OUTCOME);
            case LedgerAnswer.Refused<Ledger.HoldSettled> refused -> new StepOutcome.TechnicalFailure(
                    "ledger-service refused the settlement: " + refused.refusal().code(), false);
            case LedgerAnswer.Unavailable<Ledger.HoldSettled> unavailable ->
                    new StepOutcome.TechnicalFailure(unavailable.detail(), unavailable.retryable());
        };
    }

    /** Never: the CSM moved the money, so the payer must be debited (§6.2). */
    @Override
    public Optional<StepOutcome.Moved> giveUp(Payment payment, int attempts, StepOutcome.TechnicalFailure failure) {
        return Optional.empty();
    }

    @Override
    public boolean opensStuckCase() {
        return true;
    }
}
