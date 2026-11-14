package com.tpe.paymentorchestrator.application.outgoing;

import static com.tpe.paymentorchestrator.application.outgoing.OutgoingPayments.ACCEPTED;
import static com.tpe.paymentorchestrator.application.outgoing.OutgoingPayments.heldOnUs;
import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.paymentdomain.MutableClock;
import com.tpe.paymentorchestrator.application.risk.RiskAnswer;
import com.tpe.paymentorchestrator.application.runner.RunnerSettings;
import com.tpe.paymentorchestrator.application.runner.StepOutcome;
import com.tpe.paymentorchestrator.domain.payment.PaymentDirection;
import com.tpe.paymentorchestrator.domain.payment.PaymentState;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The allow and the technical failure of {@code ASSESS_PAYEE_RISK} (specs/11-payment-orchestrator.md §6.2, 4a). */
class AssessPayeeRiskActionTest {

    private final MutableClock clock = new MutableClock(ACCEPTED.plusMillis(60));
    private final List<PaymentDirection> directions = new ArrayList<>();
    private final List<Duration> timeouts = new ArrayList<>();
    private RiskAnswer answer;

    private final AssessPayeeRiskAction action = new AssessPayeeRiskAction((payment, direction, timeout) -> {
        directions.add(direction);
        timeouts.add(timeout);
        return answer;
    }, new RunnerSettings(Duration.ofSeconds(5), Duration.ofSeconds(30), 8, Duration.ofMillis(100)),
            Duration.ofMillis(500), clock);

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
    void anUnavailableRiskIsRetriedAfterTheBackoff() {
        answer = new RiskAnswer.Unavailable("timeout", true);

        var failure = (StepOutcome.TechnicalFailure) action.perform(heldOnUs());

        assertThat(failure.retryable()).isTrue();
        assertThat(action.giveUp(heldOnUs(), 1, failure)).isEmpty();
    }

    @Test
    void pastSendByThePayeeIsNotAssessed() {
        answer = new RiskAnswer.Allow();
        clock.advance(Duration.ofSeconds(10));

        assertThat(action.perform(heldOnUs())).isEqualTo(
                new StepOutcome.TechnicalFailure("no time left before send_by", false));
        assertThat(directions).isEmpty();
    }
}
