package com.tpe.paymentapi.application.submission;

import com.tpe.contracts.payment.PaymentAccepted;
import com.tpe.contracts.payment.PaymentView;
import com.tpe.contracts.payment.SubmitPayment;
import com.tpe.paymentdomain.PaymentId;

/**
 * payment-orchestrator's {@code POST /v1/payments} and {@code GET /v1/payments/{paymentId}}
 * (specs/11-payment-orchestrator.md §3.1, 10 §4).
 */
public interface Orchestrator {

    /** One attempt; retries are the caller's (specs/00-engineering-baseline.md §8). */
    Answer submit(SubmitPayment command);

    /** One attempt; retries are the caller's. */
    Read payment(PaymentId paymentId);

    /** What the orchestrator answered a submit, or that it could not be asked. */
    sealed interface Answer {}

    /** What the orchestrator answered a status read, or that it could not be asked. */
    sealed interface Read {}

    /** {@code 200}: the payment as it is now. */
    record Found(PaymentView payment) implements Read {}

    /** {@code 404 PAYMENT_NOT_FOUND}. */
    record NotFound() implements Read {}

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
    record Unavailable(Failure failure, String why) implements Answer, Read {}
}
