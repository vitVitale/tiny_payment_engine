package com.tpe.paymentorchestrator.domain.payment;

import static com.tpe.paymentorchestrator.domain.payment.PaymentState.ACCEPTED;
import static com.tpe.paymentorchestrator.domain.payment.PaymentState.ACCEPT_REQUESTED;
import static com.tpe.paymentorchestrator.domain.payment.PaymentState.FUNDS_RESERVED;
import static com.tpe.paymentorchestrator.domain.payment.PaymentState.INVESTIGATING;
import static com.tpe.paymentorchestrator.domain.payment.PaymentState.RECEIVED;
import static com.tpe.paymentorchestrator.domain.payment.PaymentState.REJECTED;
import static com.tpe.paymentorchestrator.domain.payment.PaymentState.RISK_CHECKED;
import static com.tpe.paymentorchestrator.domain.payment.PaymentState.SENT_TO_CSM;
import static com.tpe.paymentorchestrator.domain.payment.PaymentState.SETTLED;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The allowed state moves per direction (specs/11-payment-orchestrator.md §6.1), with the moves the actions, timers
 * and CSM events of §6.2, §6.4 and §6.5 make. Every non-final state may end in {@code REJECTED}; a final state is
 * never left.
 */
public final class PaymentStateMachine {

    private static final Map<PaymentState, Set<PaymentState>> OUTGOING = Map.of(
            RECEIVED, EnumSet.of(RISK_CHECKED, REJECTED),
            RISK_CHECKED, EnumSet.of(FUNDS_RESERVED, REJECTED),
            // SETTLED directly: on-us
            FUNDS_RESERVED, EnumSet.of(SENT_TO_CSM, SETTLED, REJECTED),
            SENT_TO_CSM, EnumSet.of(SETTLED, REJECTED, INVESTIGATING),
            INVESTIGATING, EnumSet.of(SETTLED, REJECTED));

    private static final Map<PaymentState, Set<PaymentState>> INCOMING = Map.of(
            RECEIVED, EnumSet.of(ACCEPT_REQUESTED, REJECTED),
            // SETTLED directly: the settlement report overtook AnswerSent(ACCP)
            ACCEPT_REQUESTED, EnumSet.of(ACCEPTED, SETTLED, REJECTED),
            ACCEPTED, EnumSet.of(SETTLED, REJECTED, INVESTIGATING),
            INVESTIGATING, EnumSet.of(SETTLED, REJECTED));

    private PaymentStateMachine() {}

    public static boolean allows(PaymentDirection direction, PaymentState from, PaymentState to) {
        var moves = direction == PaymentDirection.OUTGOING ? OUTGOING : INCOMING;
        return moves.getOrDefault(from, Set.of()).contains(to);
    }

    /** @throws IllegalTransitionException if the move is not allowed */
    public static void require(PaymentDirection direction, PaymentState from, PaymentState to) {
        if (!allows(direction, from, to)) {
            throw new IllegalTransitionException(direction, from, to);
        }
    }
}
