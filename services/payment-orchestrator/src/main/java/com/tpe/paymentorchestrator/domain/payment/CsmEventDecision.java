package com.tpe.paymentorchestrator.domain.payment;

import com.tpe.paymentdomain.StatusReason;
import com.tpe.paymentorchestrator.domain.payment.CsmReport.NotSent;
import com.tpe.paymentorchestrator.domain.payment.CsmReport.Rejected;
import com.tpe.paymentorchestrator.domain.payment.CsmReport.Settled;
import java.time.Instant;
import java.util.Objects;

/**
 * What a CSM event does to a payment (specs/11-payment-orchestrator.md §6.5); an outgoing one (UC-04 6–8, UC-05 2a):
 *
 * <ul>
 *   <li>with the CSM ({@code SENT_TO_CSM}, {@code INVESTIGATING}) and no outcome yet: settled → {@code SETTLE_HOLD}
 *       next; rejected or not sent → {@code REJECTED} with the reason, {@code RELEASE_HOLD} next. With the CSM and an
 *       outcome recorded, the CSM settled (a rejection would have made it {@code REJECTED}): its hold is being
 *       settled, or was released under it;
 *   <li>the same final outcome again → no-op;
 *   <li>a contradicting outcome, or any event before the payment was sent (the gateway cannot know it yet) → a
 *       {@code CONTRADICTING_CSM_OUTCOME} case; the payment stays as it is.
 * </ul>
 *
 * An incoming one is decided by the same rules on its own states (UC-06 8a, 9): see {@code decideIncoming}.
 */
public final class CsmEventDecision {

    public sealed interface Decision permits Apply, NoOp, Contradiction {}

    /** The payment moves on; {@code outcome} is recorded and its timers are cancelled. */
    public record Apply(Payment next, String cause, CsmOutcome outcome) implements Decision {

        public Apply {
            Objects.requireNonNull(next, "next");
            Objects.requireNonNull(cause, "cause");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /** Nothing changes: the payment has this outcome already. */
    public record NoOp(String why) implements Decision {}

    /** The event contradicts what the payment is: a {@code CONTRADICTING_CSM_OUTCOME} case, nothing else. */
    public record Contradiction(String why) implements Decision {}

    private CsmEventDecision() {}

    /** @param csmOutcomeRecorded {@code csm_outcome} is set */
    public static Decision decide(Payment payment, boolean csmOutcomeRecorded, CsmReport event,
            Instant now) {
        if (payment.direction() == PaymentDirection.INCOMING) {
            return decideIncoming(payment, csmOutcomeRecorded, event, now);
        }
        PaymentState state = payment.state();
        boolean withCsm = state.isWithCsm();
        if (!withCsm && !state.isFinal()) {
            return new Contradiction(event.getClass().getSimpleName() + " in " + state + ": not sent yet");
        }
        boolean settlementRecorded = withCsm
                && (csmOutcomeRecorded || payment.pendingAction() == PendingAction.SETTLE_HOLD);
        return switch (event) {
            case Settled settled -> {
                if (settlementRecorded || state == PaymentState.SETTLED) {
                    yield new NoOp("settled already");
                }
                yield withCsm ? new Apply(payment.csmSettled(), "CSM_SETTLED", CsmOutcome.SETTLED)
                        : new Contradiction("settled after " + state);
            }
            case Rejected rejected -> rejection(payment, settlementRecorded, rejected.reason(), "CSM_REJECTED",
                    CsmOutcome.REJECTED, now);
            case NotSent notSent -> rejection(payment, settlementRecorded, notSent.reason(), "CSM_NOT_SENT",
                    CsmOutcome.NOT_SENT, now);
        };
    }

    /**
     * An incoming payment (UC-06 8a, 9): accepted ({@code ACCEPT_REQUESTED}, {@code ACCEPTED}, {@code INVESTIGATING})
     * with no outcome yet: settled → {@code CONFIRM_CREDIT} next; rejected → {@code REJECTED} with the reason,
     * {@code CANCEL_CREDIT} next. Accepted with an outcome recorded, the CSM settled: its credit is being confirmed,
     * or was cancelled under it. The same final outcome again → no-op; a contradicting one, or one before any
     * accepting answer ({@code RECEIVED}: the gateway publishes in causal order, 02 §4) → a case.
     */
    private static Decision decideIncoming(Payment payment, boolean csmOutcomeRecorded, CsmReport event,
            Instant now) {
        PaymentState state = payment.state();
        boolean accepted = payment.isAcceptedIncoming();
        if (!accepted && !state.isFinal()) {
            return new Contradiction(event.getClass().getSimpleName() + " in " + state + ": not answered accepting");
        }
        boolean settlementRecorded = accepted
                && (csmOutcomeRecorded || payment.pendingAction() == PendingAction.CONFIRM_CREDIT);
        return switch (event) {
            case Settled settled -> {
                if (settlementRecorded || state == PaymentState.SETTLED) {
                    yield new NoOp("settled already");
                }
                yield accepted ? new Apply(payment.csmSettled(), "CSM_SETTLED", CsmOutcome.SETTLED)
                        : new Contradiction("settled after " + state);
            }
            case Rejected rejected -> {
                if (state == PaymentState.REJECTED) {
                    yield new NoOp("rejected already");
                }
                if (settlementRecorded || state == PaymentState.SETTLED) {
                    yield new Contradiction("REJECTED after the CSM settled");
                }
                yield new Apply(payment.rejected(rejected.reason(), PendingAction.CANCEL_CREDIT, now),
                        "CSM_REJECTED", CsmOutcome.REJECTED);
            }
            case NotSent notSent -> throw new IllegalArgumentException("an incoming payment is never sent: " + payment);
        };
    }

    private static Decision rejection(Payment payment, boolean settlementRecorded, StatusReason reason, String cause,
            CsmOutcome outcome, Instant now) {
        if (payment.state() == PaymentState.REJECTED) {
            return new NoOp("rejected already");
        }
        if (settlementRecorded || payment.state() == PaymentState.SETTLED) {
            return new Contradiction(outcome + " after the CSM settled");
        }
        return new Apply(payment.rejected(reason, PendingAction.RELEASE_HOLD, now), cause, outcome);
    }
}
