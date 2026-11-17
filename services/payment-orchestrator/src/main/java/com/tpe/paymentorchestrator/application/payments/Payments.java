package com.tpe.paymentorchestrator.application.payments;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.CommandId;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.StatusReason;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentorchestrator.domain.payment.CsmOutcome;
import com.tpe.paymentorchestrator.domain.payment.ExceptionCaseType;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentDirection;
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

    /** A {@code SCHEDULED} timer with {@code attempt} 0. */
    default void scheduleTimer(PaymentId paymentId, TimerType type, Instant dueAt, Instant now) {
        scheduleTimer(paymentId, type, dueAt, 0, now);
    }

    /**
     * A {@code SCHEDULED} timer.
     *
     * @param attempt what the timer counts; for {@code INVESTIGATION_RETRY} the status requests of the investigation
     *     so far
     */
    void scheduleTimer(PaymentId paymentId, TimerType type, Instant dueAt, int attempt, Instant now);

    /** Marks a timer {@code FIRED}: it is not handed over again. */
    void markTimerFired(long timerId);

    /**
     * Locks the payment's row for the current transaction ({@code FOR UPDATE}), so a timer decides on what is
     * committed and nothing changes it before the commit (§6.6).
     */
    Optional<LockedPayment> lockForTimer(PaymentId paymentId);

    /**
     * Stores what a timer made of the locked payment; a new action is due, and pending since, {@code now}. The new
     * version discards the result of an action claimed at the old one.
     *
     * @param finalEventEmitted the transaction writes the payment's final event
     */
    void applyTimer(LockedPayment locked, Payment next, boolean finalEventEmitted, Instant now);

    /**
     * Stores the locked payment gone into investigation ({@code INVESTIGATING}) as {@link #applyTimer} does, with
     * {@code investigation_started_at = now} (§6.4).
     */
    void startInvestigation(LockedPayment locked, Payment next, Instant now);

    /**
     * Counts a new status request of the payment, whose row the current transaction locked: its
     * {@code status_request_attempt} goes up by one and is never reset (§6.4), and its version moves on.
     *
     * @return the new request's {@code attempt}
     */
    int nextStatusRequestAttempt(PaymentId paymentId, Instant now);

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
     * @param paymentId {@code null} for a case without a payment (a message for no known payment, §6.5): one is
     *     open per {@code details}
     * @param details the case's {@code details} JSON object; never personal data
     * @return {@code false} if such a case was open
     */
    boolean openCase(PaymentId paymentId, ExceptionCaseType type, Map<String, Object> details, Instant now);

    /**
     * The one payment with {@code txId}, in either direction and from any debtor agent; empty if there is none or
     * more than one (a dead letter names only the record's key, §4).
     */
    Optional<PaymentId> findOnlyByTxId(TxId txId);

    /**
     * The payment of a transaction (ADR-010), locked for the current transaction ({@code FOR UPDATE}) like
     * {@link #lockForTimer}.
     */
    Optional<LockedPayment> lockByTransaction(PaymentDirection direction, Bic debtorAgentBic, TxId txId);

    /**
     * Stores what a CSM event made of the locked payment (§6.5): its state and next action, a new action due, and
     * pending since, {@code now}; records {@code outcome} at {@code now} and cancels the payment's scheduled timers
     * (§6.4). The new version discards the result of an action claimed at the old one.
     *
     * @param finalEventEmitted the transaction writes the payment's final event
     */
    void applyCsmOutcome(LockedPayment locked, Payment next, CsmOutcome outcome, boolean finalEventEmitted,
            Instant now);
}
