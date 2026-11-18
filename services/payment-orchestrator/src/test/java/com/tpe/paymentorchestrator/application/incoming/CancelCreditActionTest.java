package com.tpe.paymentorchestrator.application.incoming;

import static com.tpe.paymentorchestrator.domain.payment.IncomingPayments.RECEIVED_AT;
import static com.tpe.paymentorchestrator.domain.payment.IncomingPayments.acceptRequested;
import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.paymentdomain.MutableClock;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentorchestrator.application.ledger.Ledger;
import com.tpe.paymentorchestrator.application.ledger.LedgerAnswer;
import com.tpe.paymentorchestrator.application.ledger.LedgerRefusal;
import com.tpe.paymentorchestrator.application.outgoing.FakeLedger;
import com.tpe.paymentorchestrator.application.runner.RunnerSettings;
import com.tpe.paymentorchestrator.application.runner.StepOutcome;
import com.tpe.paymentorchestrator.domain.payment.ExceptionCaseType;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentState;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** {@code CANCEL_CREDIT} of a rejected incoming payment (specs/11-payment-orchestrator.md §6.2, ADR-009). */
class CancelCreditActionTest {

    private final MutableClock clock = new MutableClock(RECEIVED_AT.plusSeconds(6));
    private final FakeLedger ledger = new FakeLedger();

    private final CancelCreditAction action = new CancelCreditAction(ledger,
            new RunnerSettings(Duration.ofSeconds(5), Duration.ofSeconds(30), 8, Duration.ofMillis(100),
                    Duration.ofMinutes(10)),
            Duration.ofSeconds(1), clock);

    private final Payment rejected = acceptRequested().rejected(ReasonCode.AB05, PendingAction.CANCEL_CREDIT,
            RECEIVED_AT.plusSeconds(5));

    @Test
    void theCancelledCreditLeavesTheRejectionWithNothingToDo() {
        ledger.cancel = new LedgerAnswer.Done<>(new Ledger.Cancelled());

        var moved = (StepOutcome.Moved) action.perform(rejected);

        assertThat(moved.next().state()).isEqualTo(PaymentState.REJECTED);
        assertThat(moved.next().pendingAction()).isNull();
        assertThat(moved.cause()).isEqualTo("PENDING_CREDIT_CANCELLED");
        assertThat(moved.openCase()).isNull();
        assertThat(ledger.timeouts).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void aConfirmedCreditContradictsTheRejection() {
        ledger.cancel = new LedgerAnswer.Refused<>(new LedgerRefusal(LedgerRefusal.PENDING_CREDIT_CONFIRMED));

        var moved = (StepOutcome.Moved) action.perform(rejected);

        assertThat(moved.next().pendingAction()).isNull();
        assertThat(moved.cause()).isEqualTo("PENDING_CREDIT_CONFIRMED");
        assertThat(moved.openCase()).isEqualTo(ExceptionCaseType.CONTRADICTING_CSM_OUTCOME);
    }

    @Test
    void anyOtherAnswerIsRetriedForeverWithAStuckCase() {
        ledger.cancel = new LedgerAnswer.Unavailable<>("ledger-service answered 503", true);

        var failure = (StepOutcome.TechnicalFailure) action.perform(rejected);

        assertThat(failure.retryable()).isTrue();
        assertThat(action.giveUp(rejected, 1_000, failure)).isEmpty();
        assertThat(action.opensStuckCase()).isTrue();
    }
}
