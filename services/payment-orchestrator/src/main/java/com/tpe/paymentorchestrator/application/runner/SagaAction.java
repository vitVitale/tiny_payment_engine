package com.tpe.paymentorchestrator.application.runner;

import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import java.util.Optional;

/**
 * One {@code pending_action} of specs/11-payment-orchestrator.md §6.2: its remote call and what each answer means.
 * The {@link SagaRunner} claims it, calls {@link #perform} outside any transaction and applies the outcome.
 */
public interface SagaAction {

    PendingAction action();

    /** The remote call and its meaning; never throws for a remote failure. */
    StepOutcome perform(Payment payment);

    /**
     * After {@code failure} on the {@code attempts}-th try: the move that gives up, or empty to retry after the
     * back-off. A failure that is not retryable must give up.
     */
    Optional<StepOutcome.Moved> giveUp(Payment payment, int attempts, StepOutcome.TechnicalFailure failure);
}
