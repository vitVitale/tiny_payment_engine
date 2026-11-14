package com.tpe.paymentorchestrator.application.outgoing;

import static com.tpe.paymentorchestrator.application.outgoing.OutgoingPayments.ACCEPTED;
import static com.tpe.paymentorchestrator.application.outgoing.OutgoingPayments.awaitingRelease;
import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.paymentdomain.MutableClock;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentorchestrator.application.ledger.Ledger;
import com.tpe.paymentorchestrator.application.ledger.LedgerAnswer;
import com.tpe.paymentorchestrator.application.ledger.LedgerRefusal;
import com.tpe.paymentorchestrator.application.runner.RunnerSettings;
import com.tpe.paymentorchestrator.application.runner.StepOutcome;
import com.tpe.paymentorchestrator.domain.payment.ExceptionCaseType;
import com.tpe.paymentorchestrator.domain.payment.PaymentState;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** {@code RELEASE_HOLD} (specs/11-payment-orchestrator.md §6.2, ADR-009). */
class ReleaseHoldActionTest {

    private final MutableClock clock = new MutableClock(ACCEPTED.plusSeconds(2));
    private final FakeLedger ledger = new FakeLedger();
    private final ReleaseHoldAction action = new ReleaseHoldAction(ledger,
            new RunnerSettings(Duration.ofSeconds(5), Duration.ofSeconds(30), 8, Duration.ofMillis(100),
                    Duration.ofMinutes(10)),
            Duration.ofSeconds(1), clock);

    @Test
    void aReleasedHoldLeavesTheRejectionWithNothingToDo() {
        ledger.release = new LedgerAnswer.Done<>(new Ledger.Released());

        var moved = (StepOutcome.Moved) action.perform(awaitingRelease());

        assertThat(moved.next().state()).isEqualTo(PaymentState.REJECTED);
        assertThat(moved.next().reasonCode()).isEqualTo(ReasonCode.AC06);
        assertThat(moved.next().pendingAction()).isNull();
        assertThat(moved.cause()).isEqualTo("HOLD_RELEASED");
        assertThat(moved.rejection()).isNull();
        assertThat(moved.openCase()).isNull();
        assertThat(ledger.timeouts).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void aSettledHoldOpensAContradictionCaseAndStopsTheRelease() {
        ledger.release = new LedgerAnswer.Refused<>(new LedgerRefusal(LedgerRefusal.HOLD_SETTLED));

        var moved = (StepOutcome.Moved) action.perform(awaitingRelease());

        assertThat(moved.next().pendingAction()).isNull();
        assertThat(moved.cause()).isEqualTo("HOLD_SETTLED");
        assertThat(moved.openCase()).isEqualTo(ExceptionCaseType.CONTRADICTING_CSM_OUTCOME);
    }

    @Test
    void aTechnicalFailureIsRetriedForeverAndReportedWhenStuck() {
        ledger.release = new LedgerAnswer.Unavailable<>("ledger-service answered 503", true);

        var failure = (StepOutcome.TechnicalFailure) action.perform(awaitingRelease());

        assertThat(action.giveUp(awaitingRelease(), 10_000, failure)).isEmpty();
        assertThat(action.opensStuckCase()).isTrue();
    }

    @Test
    void anyOtherRefusalIsRetriedAsWell() {
        ledger.release = new LedgerAnswer.Refused<>(new LedgerRefusal("VALIDATION_FAILED"));

        var failure = (StepOutcome.TechnicalFailure) action.perform(awaitingRelease());

        assertThat(failure.retryable()).isFalse();
        assertThat(action.giveUp(awaitingRelease(), 1, failure)).isEmpty();
    }
}
