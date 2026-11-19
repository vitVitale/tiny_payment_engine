package com.tpe.paymentorchestrator.application.incoming;

import static com.tpe.paymentorchestrator.domain.payment.IncomingPayments.RECEIVED_AT;
import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.paymentdomain.MutableClock;
import com.tpe.paymentorchestrator.application.ledger.Ledger;
import com.tpe.paymentorchestrator.application.ledger.LedgerAnswer;
import com.tpe.paymentorchestrator.application.ledger.LedgerRefusal;
import com.tpe.paymentorchestrator.application.outgoing.FakeLedger;
import com.tpe.paymentorchestrator.application.runner.RunnerSettings;
import com.tpe.paymentorchestrator.application.runner.StepOutcome;
import com.tpe.paymentorchestrator.domain.payment.AnswerDecision;
import com.tpe.paymentorchestrator.domain.payment.ExceptionCaseType;
import com.tpe.paymentorchestrator.domain.payment.IncomingPayments;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentState;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@code CONFIRM_CREDIT} of an incoming payment the CSM settled (specs/11-payment-orchestrator.md §6.2, UC-06 9). */
class ConfirmCreditActionTest {

    private final MutableClock clock = new MutableClock(RECEIVED_AT.plusSeconds(3));
    private final FakeLedger ledger = new FakeLedger();

    private final ConfirmCreditAction action = new ConfirmCreditAction(ledger,
            new RunnerSettings(Duration.ofSeconds(5), Duration.ofSeconds(30), 8, Duration.ofMillis(100),
                    Duration.ofMinutes(10)),
            Duration.ofSeconds(1), clock);

    private final Payment confirming = ((AnswerDecision.Moved) AnswerDecision.decide(
            IncomingPayments.acceptRequested(), AnswerDecision.Answer.ACCP, null, RECEIVED_AT.plusSeconds(1)))
            .next().csmSettled();

    @Test
    void theConfirmedCreditSettlesThePaymentToThePayee() {
        UUID entry = UUID.randomUUID();
        UUID payee = UUID.randomUUID();
        ledger.confirm = new LedgerAnswer.Done<>(new Ledger.CreditConfirmed(entry, payee));

        var moved = (StepOutcome.Moved) action.perform(confirming);

        assertThat(moved.next().state()).isEqualTo(PaymentState.SETTLED);
        assertThat(moved.next().pendingAction()).isNull();
        assertThat(moved.next().bookingEntryId()).isEqualTo(entry);
        assertThat(moved.next().creditorAccountId()).isEqualTo(payee);
        assertThat(moved.next().finalisedAt()).isEqualTo(clock.instant());
        assertThat(moved.cause()).isEqualTo("CREDIT_CONFIRMED");
        assertThat(moved.openCase()).isNull();
        assertThat(ledger.timeouts).as("no deadline: the CSM settled").containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void aCancelledCreditContradictsTheSettlement() {
        ledger.confirm = new LedgerAnswer.Refused<>(new LedgerRefusal(LedgerRefusal.PENDING_CREDIT_CANCELLED));

        var moved = (StepOutcome.Moved) action.perform(confirming);

        assertThat(moved.next().state()).isEqualTo(PaymentState.ACCEPTED);
        assertThat(moved.next().pendingAction()).isNull();
        assertThat(moved.cause()).isEqualTo("PENDING_CREDIT_CANCELLED");
        assertThat(moved.openCase()).isEqualTo(ExceptionCaseType.CONTRADICTING_CSM_OUTCOME);
    }

    @Test
    void anyOtherAnswerIsRetriedForeverWithAStuckCase() {
        ledger.confirm = new LedgerAnswer.Unavailable<>("ledger-service answered 503", true);

        var failure = (StepOutcome.TechnicalFailure) action.perform(confirming);

        assertThat(failure.retryable()).isTrue();
        assertThat(action.giveUp(confirming, 1_000, failure)).isEmpty();
        assertThat(action.opensStuckCase()).isTrue();
    }
}
