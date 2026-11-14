package com.tpe.paymentorchestrator.application.outgoing;

import com.tpe.paymentorchestrator.application.risk.RiskAnswer;
import com.tpe.paymentorchestrator.application.risk.RiskAssessments;
import com.tpe.paymentorchestrator.application.runner.RunnerSettings;
import com.tpe.paymentorchestrator.application.runner.SagaAction;
import com.tpe.paymentorchestrator.application.runner.StepOutcome;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentDirection;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * {@code ASSESS_PAYEE_RISK} of an on-us payment (specs/11-payment-orchestrator.md §6.2, UC-04 4a): risk-service,
 * direction {@code INCOMING}, for the same payment ({@code assessmentId = <paymentId>:INCOMING}). {@code ALLOW} →
 * next {@code SETTLE_ON_US}; the state stays {@code FUNDS_RESERVED}. A technical failure is retried after the
 * back-off. {@code DENY}, {@code REVIEW} and the give-up, each ending in {@code RELEASE_HOLD}, come with #73; until
 * then they are retried and nothing is booked.
 */
public class AssessPayeeRiskAction implements SagaAction {

    private final RiskAssessments risk;
    private final RunnerSettings settings;
    private final Duration timeout;
    private final Clock clock;

    /** @param timeout {@code tpe.clients.risk.timeout.outgoing}: the payee is part of the outgoing budget */
    public AssessPayeeRiskAction(RiskAssessments risk, RunnerSettings settings, Duration timeout, Clock clock) {
        this.risk = risk;
        this.settings = settings;
        this.timeout = timeout;
        this.clock = clock;
    }

    @Override
    public PendingAction action() {
        return PendingAction.ASSESS_PAYEE_RISK;
    }

    @Override
    public StepOutcome perform(Payment payment) {
        Optional<Duration> readTimeout = settings.readTimeout(timeout, payment.sendBy(), clock.instant());
        if (readTimeout.isEmpty()) {
            return new StepOutcome.TechnicalFailure("no time left before send_by", false);
        }
        return switch (risk.assess(payment, PaymentDirection.INCOMING, readTimeout.get())) {
            case RiskAnswer.Allow allow -> new StepOutcome.Moved(payment.payeeRiskChecked(), "PAYEE_RISK_ALLOW", null);
            case RiskAnswer.Deny deny -> new StepOutcome.TechnicalFailure(
                    "payee risk DENY " + deny.reason().code() + " not handled yet (#73)", false);
            case RiskAnswer.Review review ->
                    new StepOutcome.TechnicalFailure("payee risk REVIEW not handled yet (#73)", false);
            case RiskAnswer.Unavailable unavailable ->
                    new StepOutcome.TechnicalFailure(unavailable.detail(), unavailable.retryable());
        };
    }

    /** Not yet (#73). */
    @Override
    public Optional<StepOutcome.Moved> giveUp(Payment payment, int attempts, StepOutcome.TechnicalFailure failure) {
        return Optional.empty();
    }
}
