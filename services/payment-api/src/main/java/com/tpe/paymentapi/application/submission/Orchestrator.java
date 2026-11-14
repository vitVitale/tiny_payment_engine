package com.tpe.paymentapi.application.submission;

import com.tpe.contracts.payment.PaymentAccepted;
import com.tpe.contracts.payment.SubmitPayment;

/** payment-orchestrator's {@code POST /v1/payments} (specs/11-payment-orchestrator.md §3.1, 10 §4). */
public interface Orchestrator {

    /** One attempt; retries are the caller's (specs/00-engineering-baseline.md §8). */
    Answer submit(SubmitPayment command);

    /** What the orchestrator answered, or that it could not be asked. */
    sealed interface Answer {}

    /**
     * {@code 201} or {@code 200}: the payment of this {@code commandId} (UC-01 6, 6a).
     *
     * @param created {@code false} for the payment of an earlier submit
     */
    record Accepted(PaymentAccepted payment, boolean created) implements Answer {}

    /**
     * {@code 409 COMMAND_CONFLICT}: the {@code commandId} has a payment for another request; the client used its
     * {@code Idempotency-Key} for two payments.
     */
    record CommandConflict() implements Answer {}

    /** {@code 422 CREDITOR_AGENT_NOT_REACHABLE}: the orchestrator's directory refuses the creditor bank. */
    record CreditorAgentNotReachable() implements Answer {}

    /** Not reached, no answer in time, or a {@code 5xx}. */
    record Unavailable(Failure failure, String why) implements Answer {}
}
