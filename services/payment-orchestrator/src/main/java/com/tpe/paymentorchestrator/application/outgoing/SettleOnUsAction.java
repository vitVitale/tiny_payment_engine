package com.tpe.paymentorchestrator.application.outgoing;

import com.tpe.paymentorchestrator.application.ledger.Ledger;
import com.tpe.paymentorchestrator.application.ledger.LedgerAnswer;
import com.tpe.paymentorchestrator.application.runner.RunnerSettings;
import com.tpe.paymentorchestrator.application.runner.SagaAction;
import com.tpe.paymentorchestrator.application.runner.StepOutcome;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * {@code SETTLE_ON_US} (specs/11-payment-orchestrator.md §6.2, UC-04 4a): one ledger entry Dr payer / Cr payee →
 * {@code SETTLED} with its {@code booking_entry_id}; {@code PaymentSettled} with {@code onUs = true} goes into the
 * outbox in the same transaction. The call has no deadline: once the hold exists the money moves. A technical
 * failure is retried until the ledger answers; the payee refusals and the {@code LEDGER_ACTION_STUCK} case come with
 * #73.
 */
public class SettleOnUsAction implements SagaAction {

    private final Ledger ledger;
    private final RunnerSettings settings;
    private final Duration timeout;
    private final Clock clock;

    /** @param timeout {@code tpe.clients.ledger.timeout}, 1 s */
    public SettleOnUsAction(Ledger ledger, RunnerSettings settings, Duration timeout, Clock clock) {
        this.ledger = ledger;
        this.settings = settings;
        this.timeout = timeout;
        this.clock = clock;
    }

    @Override
    public PendingAction action() {
        return PendingAction.SETTLE_ON_US;
    }

    @Override
    public StepOutcome perform(Payment payment) {
        // no deadline, so there is always time
        Duration readTimeout = settings.readTimeout(timeout, null, clock.instant()).orElseThrow();
        return switch (ledger.settleOnUs(payment, readTimeout)) {
            case LedgerAnswer.Done<Ledger.SettledOnUs> done -> new StepOutcome.Moved(payment.settledOnUs(
                    done.value().journalEntryId(), done.value().creditorAccountId(), clock.instant()),
                    "SETTLED_ON_US", null);
            case LedgerAnswer.Unavailable<Ledger.SettledOnUs> unavailable ->
                    new StepOutcome.TechnicalFailure(unavailable.detail(), unavailable.retryable());
        };
    }

    /** Never: retried until the ledger answers (§6.2). */
    @Override
    public Optional<StepOutcome.Moved> giveUp(Payment payment, int attempts, StepOutcome.TechnicalFailure failure) {
        return Optional.empty();
    }
}
