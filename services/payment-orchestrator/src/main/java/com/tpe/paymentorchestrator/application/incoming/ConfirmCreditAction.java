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
 * {@code CONFIRM_CREDIT} of an incoming payment the CSM settled (specs/11-payment-orchestrator.md §6.2, UC-06 9): the
 * ledger books the pending credit to the payee → {@code SETTLED}, and {@code PaymentSettled} follows in the same
 * transaction. {@code PENDING_CREDIT_CANCELLED} means the CSM moved the money although the credit is gone: a
 * {@code CONTRADICTING_CSM_OUTCOME} case, no final event. Anything else is retried forever, with a
 * {@code LEDGER_ACTION_STUCK} case after {@code stuck-after}.
 */
public class ConfirmCreditAction implements SagaAction {

    private final Ledger ledger;
    private final RunnerSettings settings;
    private final Duration timeout;
    private final Clock clock;

    /** @param timeout {@code tpe.clients.ledger.timeout}, 1 s */
    public ConfirmCreditAction(Ledger ledger, RunnerSettings settings, Duration timeout, Clock clock) {
        this.ledger = ledger;
        this.settings = settings;
        this.timeout = timeout;
        this.clock = clock;
    }

    @Override
    public PendingAction action() {
        return PendingAction.CONFIRM_CREDIT;
    }

    @Override
    public StepOutcome perform(Payment payment) {
        // the CSM settled: no deadline
        Duration readTimeout = settings.readTimeout(timeout, null, clock.instant()).orElseThrow();
        return switch (ledger.confirmPendingCredit(payment, readTimeout)) {
            case LedgerAnswer.Done<Ledger.CreditConfirmed> done -> new StepOutcome.Moved(payment.settledFromCredit(
                    done.value().journalEntryId(), done.value().accountId(), clock.instant()), "CREDIT_CONFIRMED", null);
            case LedgerAnswer.Refused<Ledger.CreditConfirmed> refused
                    when LedgerRefusal.PENDING_CREDIT_CANCELLED.equals(refused.refusal().code()) ->
                    new StepOutcome.Moved(payment.confirmAbandoned(), "PENDING_CREDIT_CANCELLED", null,
                            ExceptionCaseType.CONTRADICTING_CSM_OUTCOME);
            case LedgerAnswer.Refused<Ledger.CreditConfirmed> refused -> new StepOutcome.TechnicalFailure(
                    "ledger-service refused the confirm: " + refused.refusal().code(), false);
            case LedgerAnswer.Unavailable<Ledger.CreditConfirmed> unavailable ->
                    new StepOutcome.TechnicalFailure(unavailable.detail(), unavailable.retryable());
        };
    }

    /** Never: the CSM moved the money, so the payee must be credited (§6.2). */
    @Override
    public Optional<StepOutcome.Moved> giveUp(Payment payment, int attempts, StepOutcome.TechnicalFailure failure) {
        return Optional.empty();
    }

    @Override
    public boolean opensStuckCase() {
        return true;
    }
}
