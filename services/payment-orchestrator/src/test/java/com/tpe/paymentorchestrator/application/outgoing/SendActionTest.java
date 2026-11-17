package com.tpe.paymentorchestrator.application.outgoing;

import static com.tpe.paymentorchestrator.application.outgoing.OutgoingPayments.ACCEPTED;
import static com.tpe.paymentorchestrator.application.outgoing.OutgoingPayments.held;
import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.contracts.SendCreditTransfer;
import com.tpe.paymentdomain.MutableClock;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentorchestrator.application.runner.StepOutcome;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentState;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import com.tpe.paymentorchestrator.domain.payment.RejectionCause;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** {@code SEND} (specs/11-payment-orchestrator.md §6.2, UC-04 5, 5a). */
class SendActionTest {

    private final MutableClock clock = new MutableClock(ACCEPTED.plusSeconds(2));
    private final SendAction action = new SendAction(clock);

    @Test
    void beforeSendByTheCommandGoesOutAndThePaymentIsWithTheCsm() {
        Payment held = held();

        var moved = (StepOutcome.Moved) action.perform(held);

        assertThat(moved.next().state()).isEqualTo(PaymentState.SENT_TO_CSM);
        assertThat(moved.next().pendingAction()).isNull();
        assertThat(moved.cause()).isEqualTo("SENT");
        assertThat(moved.command()).isInstanceOfSatisfying(SendCreditTransfer.class, command -> {
            assertThat(command.paymentId()).isEqualTo(held.paymentId());
            assertThat(command.txId()).isEqualTo(held.txId());
            assertThat(command.uetr()).isEqualTo(held.uetr());
            assertThat(command.endToEndId()).isEqualTo(held.endToEndId());
            assertThat(command.acceptedAt()).isEqualTo(ACCEPTED);
            assertThat(command.sendBy()).isEqualTo(ACCEPTED.plusSeconds(10));
            assertThat(command.deadlineAt()).isEqualTo(ACCEPTED.plusSeconds(20));
            assertThat(command.amount()).isEqualTo(held.amount());
            assertThat(command.debtor().iban()).isEqualTo(held.debtor().iban());
            assertThat(command.debtor().accountId()).isEqualTo(held.debtorAccountId());
            assertThat(command.debtorAgentBic()).isEqualTo(held.debtor().agentBic());
            assertThat(command.creditor().iban()).isEqualTo(held.creditor().iban());
            assertThat(command.creditor().accountId()).isNull();
            assertThat(command.creditorAgentBic()).isEqualTo(held.creditor().agentBic());
        });
    }

    @Test
    void ORC_AC_06_atSendByNothingIsSentAndTheHoldIsReleased() {
        clock.set(ACCEPTED.plusSeconds(10));

        var moved = (StepOutcome.Moved) action.perform(held());

        assertThat(moved.command()).isNull();
        assertThat(moved.next().state()).isEqualTo(PaymentState.REJECTED);
        assertThat(moved.next().reasonCode()).isEqualTo(ReasonCode.AB03);
        assertThat(moved.next().pendingAction()).isEqualTo(PendingAction.RELEASE_HOLD);
        assertThat(moved.cause()).isEqualTo("SEND_BY_PASSED");
        assertThat(moved.rejection()).isEqualTo(RejectionCause.TECHNICAL);
    }

    @Test
    void aLocalStepNeverFails() {
        clock.set(ACCEPTED.plus(Duration.ofSeconds(9)).plusMillis(999));

        assertThat(action.perform(held())).isInstanceOf(StepOutcome.Moved.class);
    }
}
