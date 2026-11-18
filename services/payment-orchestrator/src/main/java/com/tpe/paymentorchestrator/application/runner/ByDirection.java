package com.tpe.paymentorchestrator.application.runner;

import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentDirection;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import java.util.Optional;

/**
 * One {@code pending_action} whose meaning depends on the payment's direction (specs/11-payment-orchestrator.md §6.2:
 * {@code ASSESS_RISK} outgoing and incoming): the runner holds one action per {@code pending_action}, this hands each
 * payment to its direction's.
 */
public final class ByDirection implements SagaAction {

    private final SagaAction outgoing;
    private final SagaAction incoming;

    /** @throws IllegalArgumentException unless both are the same {@code pending_action} */
    public ByDirection(SagaAction outgoing, SagaAction incoming) {
        if (outgoing.action() != incoming.action()) {
            throw new IllegalArgumentException(outgoing.action() + " and " + incoming.action() + " are not one action");
        }
        this.outgoing = outgoing;
        this.incoming = incoming;
    }

    @Override
    public PendingAction action() {
        return outgoing.action();
    }

    @Override
    public StepOutcome perform(Payment payment) {
        return of(payment).perform(payment);
    }

    @Override
    public Optional<StepOutcome.Moved> giveUp(Payment payment, int attempts, StepOutcome.TechnicalFailure failure) {
        return of(payment).giveUp(payment, attempts, failure);
    }

    /** Neither direction's risk assessment is a ledger action retried forever. */
    @Override
    public boolean opensStuckCase() {
        return outgoing.opensStuckCase() && incoming.opensStuckCase();
    }

    private SagaAction of(Payment payment) {
        return payment.direction() == PaymentDirection.OUTGOING ? outgoing : incoming;
    }
}
