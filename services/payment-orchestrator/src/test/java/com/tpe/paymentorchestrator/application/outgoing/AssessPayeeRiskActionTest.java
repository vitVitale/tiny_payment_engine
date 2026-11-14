package com.tpe.paymentorchestrator.application.outgoing;

import static com.tpe.paymentorchestrator.application.outgoing.OutgoingPayments.ACCEPTED;
import static com.tpe.paymentorchestrator.application.outgoing.OutgoingPayments.heldOnUs;
import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.paymentdomain.MutableClock;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentorchestrator.application.risk.RiskAnswer;
import com.tpe.paymentorchestrator.application.runner.RunnerSettings;
import com.tpe.paymentorchestrator.application.runner.StepOutcome;
import com.tpe.paymentorchestrator.domain.payment.PaymentDirection;
import com.tpe.paymentorchestrator.domain.payment.PaymentState;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import com.tpe.paymentorchestrator.domain.payment.RejectionCause;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code ASSESS_PAYEE_RISK} (specs/11-payment-orchestrator.md §6.2, UC-04 4a, 4a-1). */
class AssessPayeeRiskActionTest {

    private final MutableClock clock = new MutableClock(ACCEPTED.plusMillis(60));
    private final List<PaymentDirection> directions = new ArrayList<>();
    private final List<Duration> timeouts = new ArrayList<>();
    private RiskAnswer answer;

    private final AssessPayeeRiskAction action = new AssessPayeeRiskAction((payment, direction, timeout) -> {
        directions.add(direction);
        timeouts.add(timeout);
        return answer;
    }, new RunnerSettings(Duration.ofSeconds(5), Duration.ofSeconds(30), 8, Duration.ofMillis(100),
            Duration.ofMinutes(10)), Duration.ofMillis(500), clock);

    @Test
    void anAllowedPayeeIsSettledOnUsNextInTheIncomingDirection() {
        answer = new RiskAnswer.Allow();

        var moved = (StepOutcome.Moved) action.perform(heldOnUs());

        assertThat(moved.next().state()).isEqualTo(PaymentState.FUNDS_RESERVED);
        assertThat(moved.next().pendingAction()).isEqualTo(PendingAction.SETTLE_ON_US);
        assertThat(moved.cause()).isEqualTo("PAYEE_RISK_ALLOW");
        assertThat(moved.rejection()).isNull();
        assertThat(directions).containsExactly(PaymentDirection.INCOMING);
        assertThat(timeouts).containsExactly(Duration.ofMillis(500));
    }

    @Test
    void aDeniedPayeeRejectsWithTheReasonRiskGaveAndReleasesTheHold() {
        answer = new RiskAnswer.Deny(ReasonCode.RR04);

        var moved = (StepOutcome.Moved) action.perform(heldOnUs());

        assertThat(moved.next().state()).isEqualTo(PaymentState.REJECTED);
        assertThat(moved.next().reasonCode()).isEqualTo(ReasonCode.RR04);
        assertThat(moved.next().pendingAction()).isEqualTo(PendingAction.RELEASE_HOLD);
        assertThat(moved.next().finalisedAt()).isEqualTo(clock.instant());
        assertThat(moved.cause()).isEqualTo("PAYEE_RISK_DENY");
        assertThat(moved.rejection()).isEqualTo(RejectionCause.BUSINESS);
    }

    @Test
    void aReviewIsADenyWithFr01() {
        answer = new RiskAnswer.Review();

        var moved = (StepOutcome.Moved) action.perform(heldOnUs());

        assertThat(moved.next().reasonCode()).isEqualTo(ReasonCode.FR01);
        assertThat(moved.next().pendingAction()).isEqualTo(PendingAction.RELEASE_HOLD);
        assertThat(moved.cause()).isEqualTo("PAYEE_RISK_REVIEW");
        assertThat(moved.rejection()).isEqualTo(RejectionCause.BUSINESS);
    }

    @Test
    void anUnavailableRiskIsRetriedOnceThenThePaymentIsRejectedFr01AndReleased() {
        answer = new RiskAnswer.Unavailable("timeout", true);

        var failure = (StepOutcome.TechnicalFailure) action.perform(heldOnUs());
        assertThat(action.giveUp(heldOnUs(), 1, failure)).isEmpty();
        StepOutcome.Moved gaveUp = action.giveUp(heldOnUs(), 2, failure).orElseThrow();

        assertThat(gaveUp.next().reasonCode()).isEqualTo(ReasonCode.FR01);
        assertThat(gaveUp.next().pendingAction()).isEqualTo(PendingAction.RELEASE_HOLD);
        assertThat(gaveUp.cause()).isEqualTo("PAYEE_RISK_UNAVAILABLE");
        assertThat(gaveUp.rejection()).isEqualTo(RejectionCause.TECHNICAL);
    }

    @Test
    void pastSendByThePayeeIsNotAssessedAndTheHoldReleased() {
        answer = new RiskAnswer.Allow();
        clock.advance(Duration.ofSeconds(10));

        var failure = (StepOutcome.TechnicalFailure) action.perform(heldOnUs());

        assertThat(failure).isEqualTo(new StepOutcome.TechnicalFailure("no time left before send_by", false));
        assertThat(directions).isEmpty();
        assertThat(action.giveUp(heldOnUs(), 1, failure).orElseThrow().next().pendingAction())
                .isEqualTo(PendingAction.RELEASE_HOLD);
    }
}
