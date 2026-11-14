package com.tpe.paymentorchestrator.application.outgoing;

import static com.tpe.paymentorchestrator.application.outgoing.OutgoingPayments.ACCEPTED;
import static com.tpe.paymentorchestrator.application.outgoing.OutgoingPayments.riskChecked;
import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.paymentdomain.MutableClock;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentorchestrator.application.ledger.Ledger;
import com.tpe.paymentorchestrator.application.ledger.LedgerAnswer;
import com.tpe.paymentorchestrator.application.ledger.LedgerRefusal;
import com.tpe.paymentorchestrator.application.runner.RunnerSettings;
import com.tpe.paymentorchestrator.application.runner.StepOutcome;
import com.tpe.paymentorchestrator.domain.payment.PaymentState;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import com.tpe.paymentorchestrator.domain.payment.RejectionCause;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@code CREATE_HOLD} (specs/11-payment-orchestrator.md §6.2, UC-04 3, 3a, 3b). */
class CreateHoldActionTest {

    /** {@code send_by} is 10 s after acceptance and the give-up margin 2 s: no hold is asked for from 8 s on. */
    private static final Duration GIVE_UP_MARGIN = Duration.ofSeconds(2);

    private final MutableClock clock = new MutableClock(ACCEPTED.plusMillis(50));
    private final FakeLedger ledger = new FakeLedger();

    private final CreateHoldAction action = new CreateHoldAction(ledger,
            new RunnerSettings(Duration.ofSeconds(5), Duration.ofSeconds(30), 8, Duration.ofMillis(100),
                    Duration.ofMinutes(10)),
            Duration.ofSeconds(1), GIVE_UP_MARGIN, clock);

    @Test
    void aHeldOnUsPaymentReservesItsFundsAndAssessesThePayeeNext() {
        UUID payer = UUID.randomUUID();
        ledger.hold = new LedgerAnswer.Done<>(new Ledger.HoldCreated(payer));

        var moved = (StepOutcome.Moved) action.perform(riskChecked(true));

        assertThat(moved.next().state()).isEqualTo(PaymentState.FUNDS_RESERVED);
        assertThat(moved.next().pendingAction()).isEqualTo(PendingAction.ASSESS_PAYEE_RISK);
        assertThat(moved.next().debtorAccountId()).isEqualTo(payer);
        assertThat(moved.cause()).isEqualTo("HOLD_CREATED");
        assertThat(moved.rejection()).isNull();
        assertThat(ledger.timeouts).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void aHeldPaymentToAnotherBankIsSentNext() {
        ledger.hold = new LedgerAnswer.Done<>(new Ledger.HoldCreated(UUID.randomUUID()));

        var moved = (StepOutcome.Moved) action.perform(riskChecked(false));

        assertThat(moved.next().pendingAction()).isEqualTo(PendingAction.SEND);
    }

    @Test
    void insufficientFundsRejectsAm04WithNothingToRelease() {
        ledger.hold = new LedgerAnswer.Refused<>(new LedgerRefusal("INSUFFICIENT_FUNDS"));

        var moved = (StepOutcome.Moved) action.perform(riskChecked(true));

        assertThat(moved.next().state()).isEqualTo(PaymentState.REJECTED);
        assertThat(moved.next().reasonCode()).isEqualTo(ReasonCode.AM04);
        assertThat(moved.next().pendingAction()).isNull();
        assertThat(moved.next().finalisedAt()).isEqualTo(clock.instant());
        assertThat(moved.cause()).isEqualTo("HOLD_REFUSED");
        assertThat(moved.rejection()).isEqualTo(RejectionCause.BUSINESS);
    }

    @Test
    void aPayerWithoutCustomerNumberIsATechnicalMs03() {
        ledger.hold = new LedgerAnswer.Refused<>(new LedgerRefusal("CUSTOMER_ID_MISSING"));

        var moved = (StepOutcome.Moved) action.perform(riskChecked(true));

        assertThat(moved.next().reasonCode()).isEqualTo(ReasonCode.MS03);
        assertThat(moved.next().pendingAction()).isNull();
        assertThat(moved.rejection()).isEqualTo(RejectionCause.TECHNICAL);
    }

    @Test
    void aTechnicalFailureIsRetriedUntilTheGiveUpMarginBeforeSendBy() {
        ledger.hold = new LedgerAnswer.Unavailable<>("ledger-service answered 503", true);

        var failure = (StepOutcome.TechnicalFailure) action.perform(riskChecked(true));
        assertThat(failure).isEqualTo(new StepOutcome.TechnicalFailure("ledger-service answered 503", true));
        assertThat(action.giveUp(riskChecked(true), 7, failure)).isEmpty();

        clock.advance(Duration.ofMillis(7_950)); // send_by - 2 s
        StepOutcome.Moved gaveUp = action.giveUp(riskChecked(true), 8, failure).orElseThrow();

        assertMs03ThenRelease(gaveUp);
    }

    @Test
    void aFailureThatAskingAgainCannotMendGivesUpAtOnce() {
        ledger.hold = new LedgerAnswer.Unavailable<>("ledger-service answered 400", false);

        var failure = (StepOutcome.TechnicalFailure) action.perform(riskChecked(true));

        assertMs03ThenRelease(action.giveUp(riskChecked(true), 1, failure).orElseThrow());
    }

    @Test
    void fromTheGiveUpMarginOnNoHoldIsAskedFor() {
        clock.advance(Duration.ofMillis(7_950));

        var failure = (StepOutcome.TechnicalFailure) action.perform(riskChecked(true));

        assertThat(failure.retryable()).isFalse();
        assertThat(ledger.timeouts).isEmpty();
        assertMs03ThenRelease(action.giveUp(riskChecked(true), 3, failure).orElseThrow());
    }

    /** A create may have reached the ledger, so the release follows; its tombstone stops a late create (ADR-009). */
    private static void assertMs03ThenRelease(StepOutcome.Moved gaveUp) {
        assertThat(gaveUp.next().state()).isEqualTo(PaymentState.REJECTED);
        assertThat(gaveUp.next().reasonCode()).isEqualTo(ReasonCode.MS03);
        assertThat(gaveUp.next().pendingAction()).isEqualTo(PendingAction.RELEASE_HOLD);
        assertThat(gaveUp.cause()).isEqualTo("HOLD_UNAVAILABLE");
        assertThat(gaveUp.rejection()).isEqualTo(RejectionCause.TECHNICAL);
    }
}
