package com.tpe.paymentorchestrator.application.outgoing;

import static com.tpe.paymentorchestrator.application.outgoing.OutgoingPayments.ACCEPTED;
import static com.tpe.paymentorchestrator.application.outgoing.OutgoingPayments.riskChecked;
import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.paymentdomain.MutableClock;
import com.tpe.paymentorchestrator.application.ledger.Ledger;
import com.tpe.paymentorchestrator.application.ledger.LedgerAnswer;
import com.tpe.paymentorchestrator.application.runner.RunnerSettings;
import com.tpe.paymentorchestrator.application.runner.StepOutcome;
import com.tpe.paymentorchestrator.domain.payment.PaymentState;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The success and the technical failure of {@code CREATE_HOLD} (specs/11-payment-orchestrator.md §6.2, UC-04 3). */
class CreateHoldActionTest {

    private final MutableClock clock = new MutableClock(ACCEPTED.plusMillis(50));
    private final FakeLedger ledger = new FakeLedger();

    private final CreateHoldAction action = new CreateHoldAction(ledger,
            new RunnerSettings(Duration.ofSeconds(5), Duration.ofSeconds(30), 8, Duration.ofMillis(100)),
            Duration.ofSeconds(1), clock);

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
    void aTechnicalFailureIsRetriedAfterTheBackoff() {
        ledger.hold = new LedgerAnswer.Unavailable<>("ledger-service answered 503", true);

        var failure = (StepOutcome.TechnicalFailure) action.perform(riskChecked(true));

        assertThat(failure).isEqualTo(new StepOutcome.TechnicalFailure("ledger-service answered 503", true));
        assertThat(action.giveUp(riskChecked(true), 7, failure)).isEmpty();
    }

    @Test
    void closeToSendByTheCallGetsOnlyTheTimeThatIsLeft() {
        ledger.hold = new LedgerAnswer.Done<>(new Ledger.HoldCreated(UUID.randomUUID()));
        clock.advance(Duration.ofMillis(9_350)); // 600 ms before send_by

        action.perform(riskChecked(true));

        assertThat(ledger.timeouts).containsExactly(Duration.ofMillis(500));
    }

    @Test
    void pastSendByNoHoldIsCreated() {
        clock.advance(Duration.ofSeconds(10));

        assertThat(action.perform(riskChecked(true))).isEqualTo(
                new StepOutcome.TechnicalFailure("no time left before send_by", false));
        assertThat(ledger.timeouts).isEmpty();
    }
}
