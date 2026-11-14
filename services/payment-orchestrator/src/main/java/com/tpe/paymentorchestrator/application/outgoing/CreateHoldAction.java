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
 * {@code CREATE_HOLD} of an outgoing payment (specs/11-payment-orchestrator.md §6.2, UC-04 3): the ledger reserves
 * the amount on the payer's account → {@code FUNDS_RESERVED}, next {@code ASSESS_PAYEE_RISK} if on-us, else
 * {@code SEND}. The hold id and idempotency key derive from the {@code paymentId}, so a retry after a lost answer
 * finds the same hold. A technical failure is retried after the back-off; the give-up at
 * {@code send_by − hold-give-up-margin} and the ledger's refusals come with #73.
 */
public class CreateHoldAction implements SagaAction {

    private final Ledger ledger;
    private final RunnerSettings settings;
    private final Duration timeout;
    private final Clock clock;

    /** @param timeout {@code tpe.clients.ledger.timeout}, 1 s */
    public CreateHoldAction(Ledger ledger, RunnerSettings settings, Duration timeout, Clock clock) {
        this.ledger = ledger;
        this.settings = settings;
        this.timeout = timeout;
        this.clock = clock;
    }

    @Override
    public PendingAction action() {
        return PendingAction.CREATE_HOLD;
    }

    @Override
    public StepOutcome perform(Payment payment) {
        Optional<Duration> readTimeout = settings.readTimeout(timeout, payment.sendBy(), clock.instant());
        if (readTimeout.isEmpty()) {
            return new StepOutcome.TechnicalFailure("no time left before send_by", false);
        }
        return switch (ledger.createHold(payment, readTimeout.get())) {
            case LedgerAnswer.Done<Ledger.HoldCreated> done ->
                    new StepOutcome.Moved(payment.fundsReserved(done.value().accountId()), "HOLD_CREATED", null);
            case LedgerAnswer.Unavailable<Ledger.HoldCreated> unavailable ->
                    new StepOutcome.TechnicalFailure(unavailable.detail(), unavailable.retryable());
        };
    }

    /** Not yet: the give-up and the deadline timer that ends a payment without a hold come with #73 and #74. */
    @Override
    public Optional<StepOutcome.Moved> giveUp(Payment payment, int attempts, StepOutcome.TechnicalFailure failure) {
        return Optional.empty();
    }
}
