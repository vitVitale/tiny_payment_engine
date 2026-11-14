package com.tpe.paymentapi.application.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.contracts.Direction;
import com.tpe.contracts.payment.PaymentState;
import com.tpe.contracts.payment.PaymentView;
import com.tpe.contracts.payment.SubmitPayment;
import com.tpe.paymentapi.application.submission.Failure;
import com.tpe.paymentapi.application.submission.Orchestrator;
import com.tpe.paymentapi.application.submission.OrchestratorUnavailableException;
import com.tpe.paymentdomain.PaymentId;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** specs/10-payment-api.md §3.2, §4: the status read, its retry and what this API shows. */
class PaymentStatusesTest {

    private static final PaymentId ID = new PaymentId(UUID.fromString("0192f0e4-7b1c-7cc3-a1b2-000000000101"));

    private final Reads orchestrator = new Reads();
    private final PaymentStatuses statuses = new PaymentStatuses(orchestrator);

    @Test
    void anOutgoingPaymentIsShown() {
        PaymentView payment = view(Direction.OUTGOING);
        orchestrator.answers.add(new Orchestrator.Found(payment));

        assertThat(statuses.outgoing(ID)).isEqualTo(payment);
        assertThat(orchestrator.asked).isEqualTo(1);
    }

    @Test
    void anIncomingPaymentIsNotFound() {
        orchestrator.answers.add(new Orchestrator.Found(view(Direction.INCOMING)));

        assertThatThrownBy(() -> statuses.outgoing(ID)).isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void anUnknownPaymentIsNotFound() {
        orchestrator.answers.add(new Orchestrator.NotFound());

        assertThatThrownBy(() -> statuses.outgoing(ID)).isInstanceOf(PaymentNotFoundException.class);
        assertThat(orchestrator.asked).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = Failure.class, names = {"CONNECT", "TIMEOUT", "GATEWAY"})
    void aFailedReadIsRetriedOnce(Failure failure) {
        orchestrator.answers.add(new Orchestrator.Unavailable(failure, "first"));
        orchestrator.answers.add(new Orchestrator.Found(view(Direction.OUTGOING)));

        assertThat(statuses.outgoing(ID).state()).isEqualTo(PaymentState.SETTLED);
        assertThat(orchestrator.asked).isEqualTo(2);
    }

    @Test
    void anotherServerErrorIsNotRetried() {
        orchestrator.answers.add(new Orchestrator.Unavailable(Failure.SERVER_ERROR, "500"));

        assertThatThrownBy(() -> statuses.outgoing(ID)).isInstanceOf(OrchestratorUnavailableException.class);
        assertThat(orchestrator.asked).isEqualTo(1);
    }

    @Test
    void aReadThatFailsTwiceIsUnavailable() {
        orchestrator.answers.add(new Orchestrator.Unavailable(Failure.TIMEOUT, "slow"));
        orchestrator.answers.add(new Orchestrator.Unavailable(Failure.CONNECT, "down"));

        assertThatThrownBy(() -> statuses.outgoing(ID)).isInstanceOf(OrchestratorUnavailableException.class);
        assertThat(orchestrator.asked).isEqualTo(2);
    }

    private static PaymentView view(Direction direction) {
        return new PaymentView(ID, direction, false, PaymentState.SETTLED, null, null, null, null, null, null, null,
                null, Instant.parse("2026-11-14T10:00:00Z"), null);
    }

    private static final class Reads implements Orchestrator {

        final Deque<Read> answers = new ArrayDeque<>();
        int asked;

        @Override
        public Answer submit(SubmitPayment command) {
            throw new AssertionError("never submits");
        }

        @Override
        public Read payment(PaymentId paymentId) {
            assertThat(paymentId).isEqualTo(ID);
            asked++;
            return answers.removeFirst();
        }
    }
}
