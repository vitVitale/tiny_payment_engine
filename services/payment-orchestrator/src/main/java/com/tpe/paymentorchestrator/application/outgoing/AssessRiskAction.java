package com.tpe.paymentorchestrator.application.outgoing;

import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentorchestrator.application.risk.RiskAnswer;
import com.tpe.paymentorchestrator.application.risk.RiskAssessments;
import com.tpe.paymentorchestrator.application.runner.RunnerSettings;
import com.tpe.paymentorchestrator.application.runner.SagaAction;
import com.tpe.paymentorchestrator.application.runner.StepOutcome;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import com.tpe.paymentorchestrator.domain.payment.RejectionCause;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * {@code ASSESS_RISK} of an outgoing payment (specs/11-payment-orchestrator.md §6.2, UC-04 2): risk-service with the
 * payment's {@code vopResult}. {@code ALLOW} → {@code RISK_CHECKED}, next {@code CREATE_HOLD}; {@code DENY} →
 * {@code REJECTED} with its reason; {@code REVIEW} → {@code REJECTED FR01}; no ledger call has been made, so nothing
 * is undone. Fail closed: after the second technical failure, or the first that asking again cannot mend,
 * {@code REJECTED FR01}.
 */
public class AssessRiskAction implements SagaAction {

    /** The first try and one retry (UC-04 2c). */
    static final int ATTEMPTS = 2;

    private final RiskAssessments risk;
    private final RunnerSettings settings;
    private final Duration timeout;
    private final Clock clock;

    /** @param timeout {@code tpe.clients.risk.timeout.outgoing}, 500 ms */
    public AssessRiskAction(RiskAssessments risk, RunnerSettings settings, Duration timeout, Clock clock) {
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
        Optional<Duration> readTimeout = settings.readTimeout(timeout, payment.sendBy(), clock.instant());
        if (readTimeout.isEmpty()) {
            // asking again only gets closer to send_by
            return new StepOutcome.TechnicalFailure("no time left before send_by", false);
        }
        RiskAnswer answer = risk.assess(payment, payment.direction(), readTimeout.get());
        Instant now = clock.instant();
        return switch (answer) {
            case RiskAnswer.Allow allow -> new StepOutcome.Moved(payment.riskChecked(), "RISK_ALLOW", null);
            case RiskAnswer.Deny deny -> new StepOutcome.Moved(payment.rejected(deny.reason(), null, now),
                    "RISK_DENY", RejectionCause.BUSINESS);
            case RiskAnswer.Review review -> new StepOutcome.Moved(payment.rejected(ReasonCode.FR01, null, now),
                    "RISK_REVIEW", RejectionCause.BUSINESS);
            case RiskAnswer.Unavailable unavailable ->
                    new StepOutcome.TechnicalFailure(unavailable.detail(), unavailable.retryable());
        };
    }

    @Override
    public Optional<StepOutcome.Moved> giveUp(Payment payment, int attempts, StepOutcome.TechnicalFailure failure) {
        if (failure.retryable() && attempts < ATTEMPTS) {
            return Optional.empty();
        }
        return Optional.of(new StepOutcome.Moved(payment.rejected(ReasonCode.FR01, null, clock.instant()),
                "RISK_UNAVAILABLE", RejectionCause.TECHNICAL));
    }
}
