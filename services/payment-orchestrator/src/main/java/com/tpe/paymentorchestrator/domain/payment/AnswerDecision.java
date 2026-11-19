package com.tpe.paymentorchestrator.domain.payment;

import com.tpe.paymentdomain.StatusReason;
import java.time.Instant;
import java.util.Objects;

/**
 * What the gateway's answer to an incoming payment ({@code AnswerSent}) does to it (specs/11-payment-orchestrator.md
 * §6.5, UC-06 6–7, ADR-003): the payment follows the answer actually sent, whatever its verdict was.
 *
 * <ul>
 *   <li>{@code ACCP} in {@code ACCEPT_REQUESTED} → {@code ACCEPTED}, keeping the credit's confirmation if the CSM's
 *       settlement overtook the answer; final with the verdict {@code ACCEPT} (the CSM's outcome overtook the answer) a
 *       no-op; in any other state a {@code CONTRADICTING_CSM_OUTCOME} case: we never sent an accepting verdict for it;
 *   <li>{@code RJCT} in {@code RECEIVED} or {@code ACCEPT_REQUESTED} → {@code REJECTED} with the sent reason, next
 *       {@code CANCEL_CREDIT}: a pending credit may have been created (the cancel tombstones one that was not); with
 *       the CSM's settlement recorded ({@code CONFIRM_CREDIT} pending) a contradiction; in {@code REJECTED} a no-op; in
 *       a state after the accepting answer a contradiction.
 * </ul>
 */
public final class AnswerDecision {

    /** The status of the answer {@code pacs.002}. */
    public enum Answer {
        ACCP,
        RJCT
    }

    public sealed interface Decision permits Moved, NoOp, Contradiction {}

    /** The payment moves on; {@code cause} is the transition's. */
    public record Moved(Payment next, String cause) implements Decision {

        public Moved {
            Objects.requireNonNull(next, "next");
            Objects.requireNonNull(cause, "cause");
        }
    }

    /** Nothing changes: the payment follows this answer already. */
    public record NoOp(String why) implements Decision {}

    /** The answer contradicts what the payment is: a {@code CONTRADICTING_CSM_OUTCOME} case, nothing else. */
    public record Contradiction(String why) implements Decision {}

    private AnswerDecision() {}

    /**
     * @param reason the answer's reason; present exactly on {@code RJCT}
     * @throws IllegalArgumentException for an outgoing payment
     */
    public static Decision decide(Payment payment, Answer answer, StatusReason reason, Instant now) {
        if (payment.direction() != PaymentDirection.INCOMING) {
            throw new IllegalArgumentException("only an incoming payment is answered, not " + payment);
        }
        PaymentState state = payment.state();
        return switch (answer) {
            case ACCP -> {
                if (state == PaymentState.ACCEPT_REQUESTED) {
                    yield new Moved(payment.answerAccepted(), "ANSWER_ACCP");
                }
                // the CSM's outcome overtook the answer: the inbox drops a repeated ACCP, so only this way is it late
                boolean overtaken = state.isFinal() && payment.incoming().verdict() == Verdict.ACCEPT;
                yield overtaken ? new NoOp("the CSM's outcome overtook the accepting answer")
                        : new Contradiction("accepted in " + state + ": no accepting verdict was sent");
            }
            case RJCT -> switch (state) {
                case RECEIVED, ACCEPT_REQUESTED -> payment.pendingAction() == PendingAction.CONFIRM_CREDIT
                        ? new Contradiction("rejected after the CSM settled")
                        : new Moved(payment.rejected(Objects.requireNonNull(reason, "reason"),
                                PendingAction.CANCEL_CREDIT, now), "ANSWER_RJCT");
                case REJECTED -> new NoOp("rejected already");
                default -> new Contradiction("rejected in " + state + ", after the accepting answer");
            };
        };
    }
}
