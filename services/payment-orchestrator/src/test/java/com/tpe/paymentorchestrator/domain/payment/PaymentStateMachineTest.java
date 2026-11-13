package com.tpe.paymentorchestrator.domain.payment;

import static com.tpe.paymentorchestrator.domain.payment.PaymentDirection.INCOMING;
import static com.tpe.paymentorchestrator.domain.payment.PaymentDirection.OUTGOING;
import static com.tpe.paymentorchestrator.domain.payment.PaymentState.ACCEPTED;
import static com.tpe.paymentorchestrator.domain.payment.PaymentState.ACCEPT_REQUESTED;
import static com.tpe.paymentorchestrator.domain.payment.PaymentState.FUNDS_RESERVED;
import static com.tpe.paymentorchestrator.domain.payment.PaymentState.INVESTIGATING;
import static com.tpe.paymentorchestrator.domain.payment.PaymentState.RECEIVED;
import static com.tpe.paymentorchestrator.domain.payment.PaymentState.REJECTED;
import static com.tpe.paymentorchestrator.domain.payment.PaymentState.RISK_CHECKED;
import static com.tpe.paymentorchestrator.domain.payment.PaymentState.SENT_TO_CSM;
import static com.tpe.paymentorchestrator.domain.payment.PaymentState.SETTLED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The payment state machine of specs/11-payment-orchestrator.md §6.1, written out pair by pair. */
class PaymentStateMachineTest {

    private record Move(PaymentDirection direction, PaymentState from, PaymentState to) {}

    /** §6.1 with the effects of §6.2, §6.4 and §6.5. */
    private static final Set<Move> ALLOWED = Set.of(
            // outgoing: risk, hold, send or on-us, settle; rejections and the deadline from every non-final state
            new Move(OUTGOING, RECEIVED, RISK_CHECKED),
            new Move(OUTGOING, RECEIVED, REJECTED),
            new Move(OUTGOING, RISK_CHECKED, FUNDS_RESERVED),
            new Move(OUTGOING, RISK_CHECKED, REJECTED),
            new Move(OUTGOING, FUNDS_RESERVED, SENT_TO_CSM),
            new Move(OUTGOING, FUNDS_RESERVED, SETTLED),
            new Move(OUTGOING, FUNDS_RESERVED, REJECTED),
            new Move(OUTGOING, SENT_TO_CSM, SETTLED),
            new Move(OUTGOING, SENT_TO_CSM, REJECTED),
            new Move(OUTGOING, SENT_TO_CSM, INVESTIGATING),
            new Move(OUTGOING, INVESTIGATING, SETTLED),
            new Move(OUTGOING, INVESTIGATING, REJECTED),
            // incoming: verdict, answer, settlement; a settlement report may overtake AnswerSent(ACCP)
            new Move(INCOMING, RECEIVED, ACCEPT_REQUESTED),
            new Move(INCOMING, RECEIVED, REJECTED),
            new Move(INCOMING, ACCEPT_REQUESTED, ACCEPTED),
            new Move(INCOMING, ACCEPT_REQUESTED, SETTLED),
            new Move(INCOMING, ACCEPT_REQUESTED, REJECTED),
            new Move(INCOMING, ACCEPTED, SETTLED),
            new Move(INCOMING, ACCEPTED, REJECTED),
            new Move(INCOMING, ACCEPTED, INVESTIGATING),
            new Move(INCOMING, INVESTIGATING, SETTLED),
            new Move(INCOMING, INVESTIGATING, REJECTED));

    static Stream<Arguments> allowed() {
        return ALLOWED.stream().map(move -> Arguments.of(move.direction(), move.from(), move.to()));
    }

    static Stream<Arguments> refused() {
        List<Arguments> refused = new ArrayList<>();
        for (PaymentDirection direction : PaymentDirection.values()) {
            for (PaymentState from : PaymentState.values()) {
                for (PaymentState to : PaymentState.values()) {
                    if (!ALLOWED.contains(new Move(direction, from, to))) {
                        refused.add(Arguments.of(direction, from, to));
                    }
                }
            }
        }
        return refused.stream();
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @MethodSource("allowed")
    void everyAllowedTransitionSucceeds(PaymentDirection direction, PaymentState from, PaymentState to) {
        assertThat(PaymentStateMachine.allows(direction, from, to)).isTrue();
        assertThatCode(() -> PaymentStateMachine.require(direction, from, to)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @MethodSource("refused")
    void everyOtherTransitionIsRefused(PaymentDirection direction, PaymentState from, PaymentState to) {
        assertThat(PaymentStateMachine.allows(direction, from, to)).isFalse();
        assertThatThrownBy(() -> PaymentStateMachine.require(direction, from, to))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessage(direction + " payment cannot move from " + from + " to " + to);
    }

    @Test
    void aFinalStateIsNeverLeft() {
        for (PaymentDirection direction : PaymentDirection.values()) {
            for (PaymentState to : PaymentState.values()) {
                assertThat(PaymentStateMachine.allows(direction, SETTLED, to)).isFalse();
                assertThat(PaymentStateMachine.allows(direction, REJECTED, to)).isFalse();
            }
        }
        assertThat(Stream.of(PaymentState.values()).filter(PaymentState::isFinal)).containsExactly(SETTLED, REJECTED);
    }
}
