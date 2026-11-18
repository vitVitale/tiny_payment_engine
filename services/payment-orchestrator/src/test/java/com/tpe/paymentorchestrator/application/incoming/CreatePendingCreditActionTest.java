package com.tpe.paymentorchestrator.application.incoming;

import static com.tpe.paymentorchestrator.domain.payment.IncomingPayments.RECEIVED_AT;
import static com.tpe.paymentorchestrator.domain.payment.IncomingPayments.received;
import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.contracts.AcceptCreditTransfer;
import com.tpe.contracts.RejectCreditTransfer;
import com.tpe.paymentdomain.MutableClock;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentorchestrator.application.ledger.Ledger;
import com.tpe.paymentorchestrator.application.ledger.LedgerAnswer;
import com.tpe.paymentorchestrator.application.ledger.LedgerRefusal;
import com.tpe.paymentorchestrator.application.outgoing.FakeLedger;
import com.tpe.paymentorchestrator.application.runner.RunnerSettings;
import com.tpe.paymentorchestrator.application.runner.StepOutcome;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentState;
import com.tpe.paymentorchestrator.domain.payment.Verdict;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@code CREATE_PENDING_CREDIT} (specs/11-payment-orchestrator.md §6.2, UC-06 4, 4a, 5). */
class CreatePendingCreditActionTest {

    /** The answer deadline is 5 s after receipt and the give-up margin 1 s: no pending credit is asked for from 4 s on. */
    private static final Duration GIVE_UP_MARGIN = Duration.ofSeconds(1);

    private final MutableClock clock = new MutableClock(RECEIVED_AT.plusMillis(50));
    private final FakeLedger ledger = new FakeLedger();

    private final CreatePendingCreditAction action = new CreatePendingCreditAction(ledger,
            new RunnerSettings(Duration.ofSeconds(5), Duration.ofSeconds(30), 8, Duration.ofMillis(100),
                    Duration.ofMinutes(10)),
            Duration.ofSeconds(1), GIVE_UP_MARGIN, clock);

    private final Payment payment = received().incomingRiskChecked();

    @Test
    void aPendingCreditRequestsTheAcceptance() {
        UUID payee = UUID.randomUUID();
        ledger.pendingCredit = new LedgerAnswer.Done<>(new Ledger.PendingCreditCreated(payee));

        var moved = (StepOutcome.Moved) action.perform(payment);

        assertThat(moved.next().state()).isEqualTo(PaymentState.ACCEPT_REQUESTED);
        assertThat(moved.next().pendingAction()).isNull();
        assertThat(moved.next().creditorAccountId()).isEqualTo(payee);
        assertThat(moved.next().incoming().verdict()).isEqualTo(Verdict.ACCEPT);
        assertThat(moved.cause()).isEqualTo("PENDING_CREDIT_CREATED");
        assertThat(moved.command()).isEqualTo(new AcceptCreditTransfer(payment.paymentId(), payment.txId(),
                payment.debtor().agentBic(), clock.instant()));
        assertThat(ledger.timeouts).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void aBlockedPayeeIsRejectedByVerdictAc06() {
        ledger.pendingCredit = new LedgerAnswer.Refused<>(new LedgerRefusal("ACCOUNT_BLOCKED"));

        var moved = (StepOutcome.Moved) action.perform(payment);

        assertThat(moved.next().state()).isEqualTo(PaymentState.RECEIVED);
        assertThat(moved.next().incoming().verdictReason()).isEqualTo(ReasonCode.AC06);
        assertThat(moved.cause()).isEqualTo("PENDING_CREDIT_REFUSED");
        assertThat(((RejectCreditTransfer) moved.command()).reasonCode()).isEqualTo(ReasonCode.AC06);
    }

    @Test
    void aLedgerFailureIsRetriedUntilTheGiveUpMarginThenRejectedMs03() {
        var failure = new StepOutcome.TechnicalFailure("ledger-service answered 503", true);

        clock.set(RECEIVED_AT.plusMillis(3_999));
        assertThat(action.giveUp(payment, 7, failure)).isEmpty();

        clock.set(RECEIVED_AT.plusSeconds(4));
        var gaveUp = action.giveUp(payment, 8, failure).orElseThrow();
        assertThat(gaveUp.next().incoming().verdictReason()).isEqualTo(ReasonCode.MS03);
        assertThat(gaveUp.next().state()).isEqualTo(PaymentState.RECEIVED);
        assertThat(gaveUp.cause()).isEqualTo("PENDING_CREDIT_UNAVAILABLE");
        assertThat(((RejectCreditTransfer) gaveUp.command()).reasonCode()).isEqualTo(ReasonCode.MS03);
    }

    @Test
    void aFailureNotWorthRetryingRejectsMs03AtOnce() {
        assertThat(action.giveUp(payment, 1, new StepOutcome.TechnicalFailure("answer unreadable", false)))
                .hasValueSatisfying(gaveUp -> assertThat(gaveUp.next().incoming().verdictReason())
                        .isEqualTo(ReasonCode.MS03));
    }

    @Test
    void noPendingCreditIsAskedForPastTheGiveUpMargin() {
        clock.set(RECEIVED_AT.plusSeconds(4));

        var failure = (StepOutcome.TechnicalFailure) action.perform(payment);

        assertThat(failure.retryable()).isFalse();
        assertThat(ledger.timeouts).isEmpty();
    }

    @Test
    void theCallLeavesTheSafetyMarginBeforeTheAnswerDeadline() {
        ledger.pendingCredit = new LedgerAnswer.Done<>(new Ledger.PendingCreditCreated(UUID.randomUUID()));
        clock.set(RECEIVED_AT.plusMillis(3_500));

        action.perform(payment);

        assertThat(ledger.timeouts).containsExactly(Duration.ofSeconds(1));
        clock.set(RECEIVED_AT.plusMillis(3_950));
        action.perform(payment);
        assertThat(ledger.timeouts).last().isEqualTo(Duration.ofMillis(950));
    }
}
