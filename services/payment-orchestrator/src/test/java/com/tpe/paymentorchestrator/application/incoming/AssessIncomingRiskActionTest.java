package com.tpe.paymentorchestrator.application.incoming;

import static com.tpe.paymentorchestrator.domain.payment.IncomingPayments.RECEIVED_AT;
import static com.tpe.paymentorchestrator.domain.payment.IncomingPayments.received;
import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.contracts.RejectCreditTransfer;
import com.tpe.paymentdomain.MutableClock;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentorchestrator.application.risk.RiskAnswer;
import com.tpe.paymentorchestrator.application.runner.RunnerSettings;
import com.tpe.paymentorchestrator.application.runner.StepOutcome;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentDirection;
import com.tpe.paymentorchestrator.domain.payment.PaymentState;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import com.tpe.paymentorchestrator.domain.payment.Verdict;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The incoming {@code ASSESS_RISK} row of specs/11-payment-orchestrator.md §6.2 and UC-06 3, 3a. */
class AssessIncomingRiskActionTest {

    private final MutableClock clock = new MutableClock(RECEIVED_AT.plusMillis(20));
    private final List<Duration> timeouts = new ArrayList<>();
    private final List<PaymentDirection> directions = new ArrayList<>();
    private RiskAnswer answer;

    private final AssessIncomingRiskAction action = new AssessIncomingRiskAction((payment, direction, timeout) -> {
        directions.add(direction);
        timeouts.add(timeout);
        return answer;
    }, new RunnerSettings(Duration.ofSeconds(5), Duration.ofSeconds(30), 8, Duration.ofMillis(100),
            Duration.ofMinutes(10)), Duration.ofMillis(300), clock);

    @Test
    void anAllowedTransferGetsItsPendingCreditNext() {
        answer = new RiskAnswer.Allow();

        var moved = (StepOutcome.Moved) action.perform(received());

        assertThat(moved.next().state()).isEqualTo(PaymentState.RECEIVED);
        assertThat(moved.next().pendingAction()).isEqualTo(PendingAction.CREATE_PENDING_CREDIT);
        assertThat(moved.cause()).isEqualTo("RISK_ALLOW");
        assertThat(moved.command()).isNull();
        assertThat(directions).containsExactly(PaymentDirection.INCOMING);
        assertThat(timeouts).containsExactly(Duration.ofMillis(300));
    }

    @Test
    void aDeniedTransferIsRejectedByVerdictAndStaysReceived() {
        answer = new RiskAnswer.Deny(ReasonCode.FR01);
        Payment payment = received();

        var moved = (StepOutcome.Moved) action.perform(payment);

        assertThat(moved.next().state()).isEqualTo(PaymentState.RECEIVED);
        assertThat(moved.next().pendingAction()).isNull();
        assertThat(moved.next().incoming().verdict()).isEqualTo(Verdict.REJECT);
        assertThat(moved.next().incoming().verdictReason()).isEqualTo(ReasonCode.FR01);
        assertThat(moved.cause()).isEqualTo("RISK_DENY");
        assertThat(moved.command()).isEqualTo(new RejectCreditTransfer(payment.paymentId(), payment.txId(),
                payment.debtor().agentBic(), clock.instant(), ReasonCode.FR01));
    }

    @Test
    void aSanctionsDenialKeepsItsReasonAndAReviewIsFr01() {
        answer = new RiskAnswer.Deny(ReasonCode.RR04);
        assertThat(((StepOutcome.Moved) action.perform(received())).next().incoming().verdictReason())
                .isEqualTo(ReasonCode.RR04);

        answer = new RiskAnswer.Review();
        var moved = (StepOutcome.Moved) action.perform(received());
        assertThat(moved.next().incoming().verdictReason()).isEqualTo(ReasonCode.FR01);
        assertThat(moved.cause()).isEqualTo("RISK_REVIEW");
    }

    @Test
    void theCallLeavesTheSafetyMarginBeforeTheAnswerDeadline() {
        answer = new RiskAnswer.Allow();
        clock.set(RECEIVED_AT.plusMillis(4_750));

        action.perform(received());

        assertThat(timeouts).containsExactly(Duration.ofMillis(150));
    }

    @Test
    void noTimeLeftBeforeTheAnswerDeadlineIsAFailureNotWorthRetrying() {
        clock.set(RECEIVED_AT.plusMillis(4_950));

        var failure = (StepOutcome.TechnicalFailure) action.perform(received());

        assertThat(failure.retryable()).isFalse();
        assertThat(timeouts).isEmpty();
    }

    @Test
    void oneRetryThenFailClosedWithAnFr01Verdict() {
        Payment payment = received();
        var failure = new StepOutcome.TechnicalFailure("risk-service not reached", true);

        assertThat(action.giveUp(payment, 1, failure)).isEmpty();
        var gaveUp = action.giveUp(payment, 2, failure).orElseThrow();

        assertThat(gaveUp.next().incoming().verdictReason()).isEqualTo(ReasonCode.FR01);
        assertThat(gaveUp.next().state()).isEqualTo(PaymentState.RECEIVED);
        assertThat(gaveUp.cause()).isEqualTo("RISK_UNAVAILABLE");
        assertThat(gaveUp.command()).isInstanceOf(RejectCreditTransfer.class);
    }
}
