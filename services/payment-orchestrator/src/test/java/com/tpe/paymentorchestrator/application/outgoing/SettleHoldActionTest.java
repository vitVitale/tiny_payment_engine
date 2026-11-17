package com.tpe.paymentorchestrator.application.outgoing;

import static com.tpe.paymentorchestrator.application.outgoing.OutgoingPayments.ACCEPTED;
import static com.tpe.paymentorchestrator.application.outgoing.OutgoingPayments.csmSettled;
import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.paymentdomain.MutableClock;
import com.tpe.paymentorchestrator.application.ledger.Ledger;
import com.tpe.paymentorchestrator.application.ledger.LedgerAnswer;
import com.tpe.paymentorchestrator.application.ledger.LedgerRefusal;
import com.tpe.paymentorchestrator.application.runner.RunnerSettings;
import com.tpe.paymentorchestrator.application.runner.StepOutcome;
import com.tpe.paymentorchestrator.domain.payment.ExceptionCaseType;
import com.tpe.paymentorchestrator.domain.payment.PaymentState;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@code SETTLE_HOLD} (specs/11-payment-orchestrator.md §6.2, UC-04 8). */
class SettleHoldActionTest {

    private final MutableClock clock = new MutableClock(ACCEPTED.plusSeconds(3));
    private final FakeLedger ledger = new FakeLedger();
    private final SettleHoldAction action = new SettleHoldAction(ledger,
            new RunnerSettings(Duration.ofSeconds(5), Duration.ofSeconds(30), 8, Duration.ofMillis(100),
                    Duration.ofMinutes(10)),
            Duration.ofSeconds(1), clock);

    @Test
    void aSettledHoldSettlesThePaymentWithItsEntry() {
        UUID entry = UUID.randomUUID();
        ledger.holdSettlement = new LedgerAnswer.Done<>(new Ledger.HoldSettled(entry));

        var moved = (StepOutcome.Moved) action.perform(csmSettled());

        assertThat(moved.next().state()).isEqualTo(PaymentState.SETTLED);
        assertThat(moved.next().bookingEntryId()).isEqualTo(entry);
        assertThat(moved.next().finalisedAt()).isEqualTo(clock.instant());
        assertThat(moved.next().pendingAction()).isNull();
        assertThat(moved.cause()).isEqualTo("HOLD_SETTLED");
        assertThat(moved.openCase()).isNull();
        assertThat(ledger.timeouts).as("no deadline after the CSM settled").containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void aReleasedHoldOpensAContradictionCaseAndStopsTheSettlement() {
        ledger.holdSettlement = new LedgerAnswer.Refused<>(new LedgerRefusal(LedgerRefusal.HOLD_RELEASED));

        var moved = (StepOutcome.Moved) action.perform(csmSettled());

        assertThat(moved.next().state()).isEqualTo(PaymentState.SENT_TO_CSM);
        assertThat(moved.next().pendingAction()).isNull();
        assertThat(moved.cause()).isEqualTo("HOLD_RELEASED");
        assertThat(moved.openCase()).isEqualTo(ExceptionCaseType.CONTRADICTING_CSM_OUTCOME);
    }

    @Test
    void aTechnicalFailureIsRetriedForeverAndReportedWhenStuck() {
        ledger.holdSettlement = new LedgerAnswer.Unavailable<>("ledger-service answered 503", true);

        var failure = (StepOutcome.TechnicalFailure) action.perform(csmSettled());

        assertThat(failure.retryable()).isTrue();
        assertThat(action.giveUp(csmSettled(), 10_000, failure)).isEmpty();
        assertThat(action.opensStuckCase()).isTrue();
    }

    @Test
    void anyOtherRefusalIsRetriedAsWell() {
        ledger.holdSettlement = new LedgerAnswer.Refused<>(new LedgerRefusal("HOLD_NOT_FOUND"));

        var failure = (StepOutcome.TechnicalFailure) action.perform(csmSettled());

        assertThat(failure.retryable()).isFalse();
        assertThat(action.giveUp(csmSettled(), 1, failure)).isEmpty();
    }
}
