package com.tpe.paymentorchestrator.application.runner;

import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.RejectionCause;
import java.util.Objects;

/** What performing a saga action came to (specs/11-payment-orchestrator.md §6.2). */
public sealed interface StepOutcome {

    /**
     * The payment moves on.
     *
     * @param cause {@code payment_transitions.cause}, e.g. {@code RISK_ALLOW}
     * @param rejection why the payment was rejected; {@code null} unless {@code next} is {@code REJECTED}
     */
    record Moved(Payment next, String cause, RejectionCause rejection) implements StepOutcome {

        public Moved {
            Objects.requireNonNull(next, "next");
            Objects.requireNonNull(cause, "cause");
        }
    }

    /**
     * The remote call failed technically; the action decides whether to retry or give up.
     *
     * @param retryable whether asking again may help (specs/00-engineering-baseline.md §8)
     */
    record TechnicalFailure(String detail, boolean retryable) implements StepOutcome {}
}
