package com.tpe.paymentorchestrator.application.runner;

import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import com.tpe.paymentorchestrator.domain.payment.RejectionCause;
import com.tpe.paymentorchestrator.domain.payment.TimerType;
import java.time.Duration;

/**
 * The saga's spans and metrics (specs/11-payment-orchestrator.md §8, specs/25-service-levels.md §11,
 * specs/26-observability.md §5.2): {@code payment.saga.action} spans as children of the payment's trace,
 * {@code tpe.saga.action.duration}, {@code tpe.payment.rejected} and {@code tpe.payment.duration}; for the timers
 * {@code payment.timer} spans, {@code tpe.timer.fired} and {@code tpe.deadline.reject}.
 */
public interface SagaTelemetry {

    /** Starts the action's span and puts it in scope on this thread until {@link ActionSpan#finished}. */
    ActionSpan actionStarted(Payment payment, PendingAction action, int attempt, boolean resumed);

    /** The payment reached {@code REJECTED}. */
    void rejected(Payment payment, RejectionCause cause);

    /** The payment reached a final state: accepted → final. */
    void finalised(Payment payment);

    /**
     * A {@code payment.timer} span as a child of the payment's trace with {@code tpe.timer.type} and
     * {@code tpe.timer.lateness_ms}, and the payment's MDC keys (specs/26-observability.md §4.3, §5.2); both in
     * scope on this thread until {@link TimerSpan#finished}.
     *
     * @param lateness fire − {@code due_at}
     */
    TimerSpan timerStarted(Payment payment, TimerType type, Duration lateness);

    /** A timer fired and its transaction committed: {@code tpe.timer.fired{type}}. */
    void timerFired(TimerType type);

    /** The scheme deadline rejected the payment: {@code tpe.deadline.reject{reason}}. */
    void deadlineRejected(Payment payment);

    interface TimerSpan {

        void finished();
    }

    /** How an action ended: {@code tpe.saga.result}. */
    enum Result {
        /** The outcome is applied. */
        OK,
        /** A technical failure; the action is due again after the back-off. */
        RETRY,
        /** A technical failure the action gave up on: the payment moved on, e.g. {@code REJECTED FR01}. */
        GAVE_UP,
        /** An event or timer overtook the step; its outcome was dropped. */
        DISCARDED,
        /** The runner itself failed; the lease expires and the sweeper hands the action over again. */
        ERROR
    }

    interface ActionSpan {

        /** The payment was rejected in this action: {@code tpe.reason} and {@code tpe.cause} on the span. */
        void rejected(Payment payment, RejectionCause cause);

        /**
         * Ends the span; a technical failure ({@code RETRY}, {@code GAVE_UP}, {@code ERROR}) gives it an error status
         * (specs/26-observability.md §5.2).
         */
        void finished(Result result);
    }
}
