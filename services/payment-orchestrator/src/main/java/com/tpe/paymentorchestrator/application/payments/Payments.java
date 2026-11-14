package com.tpe.paymentorchestrator.application.payments;

import com.tpe.paymentdomain.CommandId;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.StatusReason;
import com.tpe.paymentorchestrator.domain.payment.ExceptionCaseType;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentState;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import com.tpe.paymentorchestrator.domain.payment.TimerType;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /**
     * Claims the payment's pending action if it is one of {@code actions} and due at {@code now}: the action belongs
     * to the caller until {@code leaseUntil} and its attempts count up (specs/11-payment-orchestrator.md §6.3).
     *
     * @return empty if there is nothing due or another runner has it
     */
    Optional<ActionClaim> claim(PaymentId paymentId, Set<PendingAction> actions, Instant now, Instant leaseUntil);

    /**
     * Stores the payment's next state and action if it is still as claimed (state, action and version), and makes
     * a new action due, and pending since, {@code now}.
     *
     * @param finalEventEmitted the transaction writes the payment's final event
     * @return {@code false}: an event or timer overtook the step; the result is discarded
     */
    boolean applyStep(ActionClaim claim, Payment next, boolean finalEventEmitted, Instant now);

    /** Makes the claimed action due again at {@code at}, if the payment is still as claimed. */
    void retryAt(ActionClaim claim, Instant at, Instant now);

    /**
     * Opens an exception case of the payment unless one of its type is open already (§5).
     *
     * @param details the case's {@code details} JSON object; never personal data
     * @return {@code false} if a case of the type was open
     */
    boolean openCase(PaymentId paymentId, ExceptionCaseType type, Map<String, Object> details, Instant now);
}
