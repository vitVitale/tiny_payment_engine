package com.tpe.paymentorchestrator.application.outgoing;

import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentorchestrator.application.ledger.Ledger;
import com.tpe.paymentorchestrator.application.ledger.LedgerAnswer;
import com.tpe.paymentorchestrator.application.runner.RunnerSettings;
import com.tpe.paymentorchestrator.application.runner.SagaAction;
import com.tpe.paymentorchestrator.application.runner.StepOutcome;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import com.tpe.paymentorchestrator.domain.payment.RejectionCause;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * {@code CREATE_HOLD} of an outgoing payment (specs/11-payment-orchestrator.md §6.2, UC-04 3, 3a, 3b): the ledger
 * reserves the amount on the payer's account → {@code FUNDS_RESERVED}, next {@code ASSESS_PAYEE_RISK} if on-us, else
 * {@code SEND}. The hold id and idempotency key derive from the {@code paymentId}, so a retry after a lost answer finds
 * the same hold.
 *
 * <p>A refusal rejects with the mapped reason; no hold exists, so nothing is released. A technical failure is retried
 * after the back-off until {@code send_by − hold-give-up-margin}; then, or at once for a failure asking again cannot
 * mend, {@code REJECTED MS03} with {@code RELEASE_HOLD} next: a create may have reached the ledger, and the release's
 * tombstone refuses one that arrives late (ADR-009).
 */
public class CreateHoldAction implements SagaAction {

    private final Ledger ledger;
    private final RunnerSettings settings;
    private final Duration timeout;
    private final Duration giveUpMargin;
    private final Clock clock;

    /**
     * @param timeout {@code tpe.clients.ledger.timeout}, 1 s
     * @param giveUpMargin {@code tpe.outgoing.hold-give-up-margin}, 2 s before {@code send_by}
     */
    public CreateHoldAction(Ledger ledger, RunnerSettings settings, Duration timeout, Duration giveUpMargin,
            Clock clock) {
        this.ledger = ledger;
        this.settings = settings;
        this.timeout = timeout;
        this.giveUpMargin = giveUpMargin;
        this.clock = clock;
    }

    @Override
    public PendingAction action() {
        return PendingAction.CREATE_HOLD;
    }

    @Override
    public StepOutcome perform(Payment payment) {
        Instant now = clock.instant();
        if (pastGiveUp(payment, now)) {
            return new StepOutcome.TechnicalFailure("hold give-up margin before send_by reached", false);
        }
        Optional<Duration> readTimeout = settings.readTimeout(timeout, payment.sendBy(), now);
        if (readTimeout.isEmpty()) {
            return new StepOutcome.TechnicalFailure("no time left before send_by", false);
        }
        return switch (ledger.createHold(payment, readTimeout.get())) {
            case LedgerAnswer.Done<Ledger.HoldCreated> done ->
                    new StepOutcome.Moved(payment.fundsReserved(done.value().accountId()), "HOLD_CREATED", null);
            case LedgerAnswer.Refused<Ledger.HoldCreated> refused -> new StepOutcome.Moved(
                    payment.rejected(refused.refusal().reason(), null, clock.instant()), "HOLD_REFUSED",
                    refused.refusal().cause());
            case LedgerAnswer.Unavailable<Ledger.HoldCreated> unavailable ->
                    new StepOutcome.TechnicalFailure(unavailable.detail(), unavailable.retryable());
        };
    }

    @Override
    public Optional<StepOutcome.Moved> giveUp(Payment payment, int attempts, StepOutcome.TechnicalFailure failure) {
        Instant now = clock.instant();
        if (failure.retryable() && !pastGiveUp(payment, now)) {
            return Optional.empty();
        }
        return Optional.of(new StepOutcome.Moved(payment.rejected(ReasonCode.MS03, PendingAction.RELEASE_HOLD, now),
                "HOLD_UNAVAILABLE", RejectionCause.TECHNICAL));
    }

    private boolean pastGiveUp(Payment payment, Instant now) {
        return !now.isBefore(payment.sendBy().minus(giveUpMargin));
    }
}
