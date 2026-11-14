package com.tpe.paymentorchestrator.application.outgoing;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.CommandId;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.MutableClock;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import com.tpe.paymentorchestrator.application.risk.RiskAnswer;
import com.tpe.paymentorchestrator.application.runner.RunnerSettings;
import com.tpe.paymentorchestrator.application.runner.StepOutcome;
import com.tpe.paymentorchestrator.domain.payment.OutgoingCommand;
import com.tpe.paymentorchestrator.domain.payment.OutgoingTimings;
import com.tpe.paymentorchestrator.domain.payment.PayeeVerification;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentParty;
import com.tpe.paymentorchestrator.domain.payment.PaymentState;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import com.tpe.paymentorchestrator.domain.payment.RejectionCause;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The outgoing {@code ASSESS_RISK} row of specs/11-payment-orchestrator.md §6.2 and UC-04 2, 2a, 2b, 2c. */
class AssessRiskActionTest {

    private static final Instant ACCEPTED = Instant.parse("2026-11-13T10:00:00Z");

    private final MutableClock clock = new MutableClock(ACCEPTED.plusMillis(20));
    private final List<Duration> timeouts = new ArrayList<>();
    private RiskAnswer answer;

    private final AssessRiskAction action = new AssessRiskAction((payment, direction, timeout) -> {
        timeouts.add(timeout);
        return answer;
    }, new RunnerSettings(Duration.ofSeconds(5), Duration.ofSeconds(30), 8, Duration.ofMillis(100),
            Duration.ofMinutes(10)),
            Duration.ofMillis(500), clock);

    @Test
    void anAllowedPaymentGoesOnToTheHold() {
        answer = new RiskAnswer.Allow();

        StepOutcome.Moved moved = (StepOutcome.Moved) action.perform(submitted());

        assertThat(moved.next().state()).isEqualTo(PaymentState.RISK_CHECKED);
        assertThat(moved.next().pendingAction()).isEqualTo(PendingAction.CREATE_HOLD);
        assertThat(moved.cause()).isEqualTo("RISK_ALLOW");
        assertThat(moved.rejection()).isNull();
        assertThat(timeouts).containsExactly(Duration.ofMillis(500));
    }

    @Test
    void aDenyRejectsWithTheReasonRiskGaveAndNothingToUndo() {
        answer = new RiskAnswer.Deny(ReasonCode.RR04);

        StepOutcome.Moved moved = (StepOutcome.Moved) action.perform(submitted());

        assertThat(moved.next().state()).isEqualTo(PaymentState.REJECTED);
        assertThat(moved.next().reasonCode()).isEqualTo(ReasonCode.RR04);
        assertThat(moved.next().pendingAction()).isNull();
        assertThat(moved.next().finalisedAt()).isEqualTo(clock.instant());
        assertThat(moved.cause()).isEqualTo("RISK_DENY");
        assertThat(moved.rejection()).isEqualTo(RejectionCause.BUSINESS);
    }

    @Test
    void aReviewIsADenyWithFr01() {
        answer = new RiskAnswer.Review();

        StepOutcome.Moved moved = (StepOutcome.Moved) action.perform(submitted());

        assertThat(moved.next().reasonCode()).isEqualTo(ReasonCode.FR01);
        assertThat(moved.cause()).isEqualTo("RISK_REVIEW");
        assertThat(moved.rejection()).isEqualTo(RejectionCause.BUSINESS);
    }

    @Test
    void anUnavailableRiskIsRetriedOnceThenThePaymentIsRejectedFr01() {
        answer = new RiskAnswer.Unavailable("timeout", true);

        var failure = (StepOutcome.TechnicalFailure) action.perform(submitted());
        assertThat(failure.retryable()).isTrue();
        assertThat(action.giveUp(submitted(), 1, failure)).isEmpty();
        StepOutcome.Moved gaveUp = action.giveUp(submitted(), 2, failure).orElseThrow();

        assertThat(gaveUp.next().state()).isEqualTo(PaymentState.REJECTED);
        assertThat(gaveUp.next().reasonCode()).isEqualTo(ReasonCode.FR01);
        assertThat(gaveUp.cause()).isEqualTo("RISK_UNAVAILABLE");
        assertThat(gaveUp.rejection()).isEqualTo(RejectionCause.TECHNICAL);
    }

    @Test
    void aFailureThatAskingAgainCannotMendRejectsAtOnce() {
        answer = new RiskAnswer.Unavailable("risk-service answered 400", false);

        var failure = (StepOutcome.TechnicalFailure) action.perform(submitted());

        assertThat(action.giveUp(submitted(), 1, failure).orElseThrow().next().reasonCode())
                .isEqualTo(ReasonCode.FR01);
    }

    @Test
    void closeToSendByTheCallGetsOnlyTheTimeThatIsLeft() {
        answer = new RiskAnswer.Allow();
        clock.advance(Duration.ofMillis(9_580)); // 400 ms before send_by

        action.perform(submitted());

        assertThat(timeouts).containsExactly(Duration.ofMillis(300));
    }

    @Test
    void pastSendByRiskIsNotAsked() {
        answer = new RiskAnswer.Allow();
        clock.advance(Duration.ofSeconds(10));

        assertThat(action.perform(submitted())).isEqualTo(
                new StepOutcome.TechnicalFailure("no time left before send_by", false));
        assertThat(timeouts).isEmpty();
    }

    private static Payment submitted() {
        var command = new OutgoingCommand(new CommandId(UUID.randomUUID()), "mobile-app", new EndToEndId("INV-1"),
                new PartyName("Anna Schmidt"), new Iban("DE36100100100000000001"), new PartyName("Max Mustermann"),
                new Iban("DE07200200200123456789"), null, Money.parse("123.45"), null, "VOP-1",
                PayeeVerification.MATCH);
        return Payment.submitted(new PaymentId(UUID.randomUUID()), command, "0".repeat(64),
                new TxId("TPE01JABCDEFGHJKMNPQRSTVWXYZ"), new Uetr("6f1c2a3b-4d5e-4f60-8a1b-2c3d4e5f6a7b"),
                new PaymentParty(command.debtorName(), command.debtorIban(), new Bic("TPEBDEFFXXX")),
                new PaymentParty(command.creditorName(), command.creditorIban(), new Bic("PEERDEFFXXX")), false,
                ACCEPTED, new OutgoingTimings(Duration.ofSeconds(10), Duration.ofSeconds(20), Duration.ofSeconds(2)),
                null);
    }
}
