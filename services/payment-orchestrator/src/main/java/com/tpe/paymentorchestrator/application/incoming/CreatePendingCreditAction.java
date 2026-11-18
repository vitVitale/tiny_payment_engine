package com.tpe.paymentorchestrator.application.incoming;

import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentorchestrator.application.ledger.Ledger;
import com.tpe.paymentorchestrator.application.ledger.LedgerAnswer;
import com.tpe.paymentorchestrator.application.runner.RunnerSettings;
import com.tpe.paymentorchestrator.application.runner.SagaAction;
import com.tpe.paymentorchestrator.application.runner.StepOutcome;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * {@code CREATE_PENDING_CREDIT} of an incoming payment (specs/11-payment-orchestrator.md §6.2, UC-06 4, 4a, 5,
 * ADR-010): the ledger records the amount on the payee's account under the payment's {@code paymentId} →
 * {@code ACCEPT_REQUESTED} with {@code AcceptCreditTransfer}. A refusal is the verdict {@code REJECT} with the mapped
 * reason. A technical failure is retried after the back-off until {@code answer_deadline − verdict-give-up-margin};
 * then, or at once for a failure asking again cannot mend, the verdict {@code REJECT MS03}. Whatever the verdict, the
 * gateway's rejecting answer is followed by {@code CANCEL_CREDIT}, whose tombstone refuses a create that arrives late.
 */
public class CreatePendingCreditAction implements SagaAction {

    private final Ledger ledger;
    private final RunnerSettings settings;
    private final Duration timeout;
    private final Duration giveUpMargin;
    private final Clock clock;

    /**
     * @param timeout {@code tpe.clients.ledger.timeout}, 1 s
     * @param giveUpMargin {@code tpe.incoming.verdict-give-up-margin}, 1 s before the answer deadline
     */
    public CreatePendingCreditAction(Ledger ledger, RunnerSettings settings, Duration timeout, Duration giveUpMargin,
            Clock clock) {
        this.ledger = ledger;
        this.settings = settings;
        this.timeout = timeout;
        this.giveUpMargin = giveUpMargin;
        this.clock = clock;
    }

    @Override
    public PendingAction action() {
        return PendingAction.CREATE_PENDING_CREDIT;
    }

    @Override
    public StepOutcome perform(Payment payment) {
        Instant now = clock.instant();
        if (pastGiveUp(payment, now)) {
            return new StepOutcome.TechnicalFailure("verdict give-up margin before the answer deadline reached", false);
        }
        Optional<Duration> readTimeout = settings.readTimeout(timeout, payment.incoming().answerDeadline(), now);
        if (readTimeout.isEmpty()) {
            return new StepOutcome.TechnicalFailure("no time left before the answer deadline", false);
        }
        return switch (ledger.createPendingCredit(payment, readTimeout.get())) {
            case LedgerAnswer.Done<Ledger.PendingCreditCreated> done -> Verdicts.accept(payment,
                    done.value().accountId(), "PENDING_CREDIT_CREATED", clock.instant());
            case LedgerAnswer.Refused<Ledger.PendingCreditCreated> refused -> Verdicts.reject(payment,
                    refused.refusal().reason(), "PENDING_CREDIT_REFUSED", clock.instant());
            case LedgerAnswer.Unavailable<Ledger.PendingCreditCreated> unavailable ->
                    new StepOutcome.TechnicalFailure(unavailable.detail(), unavailable.retryable());
        };
    }

    @Override
    public Optional<StepOutcome.Moved> giveUp(Payment payment, int attempts, StepOutcome.TechnicalFailure failure) {
        Instant now = clock.instant();
        if (failure.retryable() && !pastGiveUp(payment, now)) {
            return Optional.empty();
        }
        return Optional.of(Verdicts.reject(payment, ReasonCode.MS03, "PENDING_CREDIT_UNAVAILABLE", now));
    }

    private boolean pastGiveUp(Payment payment, Instant now) {
        return !now.isBefore(payment.incoming().answerDeadline().minus(giveUpMargin));
    }
}
