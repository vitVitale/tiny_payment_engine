package com.tpe.paymentorchestrator.application.payments;

import com.tpe.paymentdomain.CommandId;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.StatusReason;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentState;
import com.tpe.paymentorchestrator.domain.payment.TimerType;
import java.time.Instant;
import java.util.Optional;

/** The payment record with its transitions and timers (specs/11-payment-orchestrator.md §5). */
public interface Payments {

    /**
     * Stores a new outgoing payment unless a payment with its {@code commandId} exists; waits for a concurrent
     * insert of the same command to finish.
     *
     * @return {@code false} if the command already had a payment
     */
    boolean insertIfNewCommand(Payment payment, Instant now);

    Optional<Payment> find(PaymentId paymentId);

    Optional<Payment> findByCommand(CommandId commandId);

    /** A row of {@code payment_transitions}; {@code from} is {@code null} for the first one. */
    void recordTransition(PaymentId paymentId, PaymentState from, PaymentState to, StatusReason reason, String cause,
            Instant at);

    /** A {@code SCHEDULED} timer. */
    void scheduleTimer(PaymentId paymentId, TimerType type, Instant dueAt, Instant now);
}
