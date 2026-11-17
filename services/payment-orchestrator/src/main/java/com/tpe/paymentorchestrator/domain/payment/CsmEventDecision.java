package com.tpe.paymentorchestrator.domain.payment;

import com.tpe.paymentdomain.StatusReason;
import com.tpe.paymentorchestrator.domain.payment.OutgoingCsmEvent.NotSent;
import com.tpe.paymentorchestrator.domain.payment.OutgoingCsmEvent.Rejected;
import com.tpe.paymentorchestrator.domain.payment.OutgoingCsmEvent.Settled;
import java.time.Instant;
import java.util.Objects;

/**
 * What a CSM event does to an outgoing payment (specs/11-payment-orchestrator.md §6.5, UC-04 6–8, UC-05 2a):
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
    public static Decision decide(Payment payment, boolean csmOutcomeRecorded, OutgoingCsmEvent event,
            Instant now) {
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
