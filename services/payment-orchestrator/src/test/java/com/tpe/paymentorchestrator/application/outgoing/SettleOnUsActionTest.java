package com.tpe.paymentorchestrator.application.outgoing;

import static com.tpe.paymentorchestrator.application.outgoing.OutgoingPayments.ACCEPTED;
import static com.tpe.paymentorchestrator.application.outgoing.OutgoingPayments.payeeChecked;
import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.paymentdomain.MutableClock;
import com.tpe.paymentorchestrator.application.ledger.Ledger;
import com.tpe.paymentorchestrator.application.ledger.LedgerAnswer;
import com.tpe.paymentorchestrator.application.runner.RunnerSettings;
import com.tpe.paymentorchestrator.application.runner.StepOutcome;
import com.tpe.paymentorchestrator.domain.payment.PaymentState;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The success and the technical failure of {@code SETTLE_ON_US} (specs/11-payment-orchestrator.md §6.2, UC-04 4a). */
class SettleOnUsActionTest {

    private final MutableClock clock = new MutableClock(ACCEPTED.plusMillis(80));
    private final FakeLedger ledger = new FakeLedger();
    private final SettleOnUsAction action = new SettleOnUsAction(ledger,
            new RunnerSettings(Duration.ofSeconds(5), Duration.ofSeconds(30), 8, Duration.ofMillis(100)),
            Duration.ofSeconds(1), clock);

    @Test
    void theSettlementIsFinalWithItsEntryAndPayeeAccount() {
        UUID entry = UUID.randomUUID();
        UUID payee = UUID.randomUUID();
        ledger.settlement = new LedgerAnswer.Done<>(new Ledger.SettledOnUs(entry, payee));

        var moved = (StepOutcome.Moved) action.perform(payeeChecked());

        assertThat(moved.next().state()).isEqualTo(PaymentState.SETTLED);
        assertThat(moved.next().pendingAction()).isNull();
        assertThat(moved.next().bookingEntryId()).isEqualTo(entry);
        assertThat(moved.next().creditorAccountId()).isEqualTo(payee);
        assertThat(moved.next().finalisedAt()).isEqualTo(clock.instant());
        assertThat(moved.cause()).isEqualTo("SETTLED_ON_US");
        assertThat(moved.rejection()).isNull();
    }

    @Test
    void theCallHasNoDeadline() {
        ledger.settlement = new LedgerAnswer.Done<>(new Ledger.SettledOnUs(UUID.randomUUID(), UUID.randomUUID()));
        // past send_by the hold exists, so the money still moves
        clock.advance(Duration.ofSeconds(15));

        action.perform(payeeChecked());

        assertThat(ledger.timeouts).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void aTechnicalFailureIsRetriedAfterTheBackoff() {
        ledger.settlement = new LedgerAnswer.Unavailable<>("ledger-service not reached", true);

        var failure = (StepOutcome.TechnicalFailure) action.perform(payeeChecked());

        assertThat(failure.retryable()).isTrue();
        assertThat(action.giveUp(payeeChecked(), 12, failure)).isEmpty();
    }
}
