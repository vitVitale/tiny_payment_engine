package com.tpe.paymentorchestrator.application.incoming;

import com.tpe.paymentdomain.ReasonCode;
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
 * {@code ASSESS_RISK} of an incoming payment (specs/11-payment-orchestrator.md §6.2, UC-06 3, 3a): risk-service,
 * direction {@code INCOMING}, its call leaving {@code safety-margin} before the answer deadline. {@code ALLOW} → next
 * {@code CREATE_PENDING_CREDIT}; {@code DENY} → the verdict {@code REJECT} with its reason; {@code REVIEW} → the
 * verdict {@code REJECT FR01}. Fail closed: after the second technical failure, or the first that asking again cannot
 * mend, the verdict {@code REJECT FR01}. The state stays {@code RECEIVED} either way.
 */
public class AssessIncomingRiskAction implements SagaAction {

    /** The first try and one retry. */
    static final int ATTEMPTS = 2;

    private final RiskAssessments risk;
    private final RunnerSettings settings;
    private final Duration timeout;
    private final Clock clock;

    /** @param timeout {@code tpe.clients.risk.timeout.incoming}, 300 ms */
    public AssessIncomingRiskAction(RiskAssessments risk, RunnerSettings settings, Duration timeout, Clock clock) {
        this.risk = risk;
        this.settings = settings;
        this.timeout = timeout;
        this.clock = clock;
    }

    @Override
    public PendingAction action() {
        return PendingAction.ASSESS_RISK;
    }

    @Override
    public StepOutcome perform(Payment payment) {
        Optional<Duration> readTimeout = settings.readTimeout(timeout, payment.incoming().answerDeadline(),
                clock.instant());
        if (readTimeout.isEmpty()) {
            // asking again only gets closer to the answer deadline
            return new StepOutcome.TechnicalFailure("no time left before the answer deadline", false);
        }
        return switch (risk.assess(payment, PaymentDirection.INCOMING, readTimeout.get())) {
            case RiskAnswer.Allow allow -> new StepOutcome.Moved(payment.incomingRiskChecked(), "RISK_ALLOW", null);
            case RiskAnswer.Deny deny -> Verdicts.reject(payment, deny.reason(), "RISK_DENY", clock.instant());
            case RiskAnswer.Review review -> Verdicts.reject(payment, ReasonCode.FR01, "RISK_REVIEW", clock.instant());
            case RiskAnswer.Unavailable unavailable ->
                    new StepOutcome.TechnicalFailure(unavailable.detail(), unavailable.retryable());
        };
    }

    @Override
    public Optional<StepOutcome.Moved> giveUp(Payment payment, int attempts, StepOutcome.TechnicalFailure failure) {
        if (failure.retryable() && attempts < ATTEMPTS) {
            return Optional.empty();
        }
        return Optional.of(Verdicts.reject(payment, ReasonCode.FR01, "RISK_UNAVAILABLE", clock.instant()));
    }
}
