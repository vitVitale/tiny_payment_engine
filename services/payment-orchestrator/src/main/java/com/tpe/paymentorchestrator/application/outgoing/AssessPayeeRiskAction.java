package com.tpe.paymentorchestrator.application.outgoing;

import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentorchestrator.application.risk.RiskAnswer;
import com.tpe.paymentorchestrator.application.risk.RiskAssessments;
import com.tpe.paymentorchestrator.application.runner.RunnerSettings;
import com.tpe.paymentorchestrator.application.runner.SagaAction;
import com.tpe.paymentorchestrator.application.runner.StepOutcome;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentDirection;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import com.tpe.paymentorchestrator.domain.payment.RejectionCause;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * {@code ASSESS_PAYEE_RISK} of an on-us payment (specs/11-payment-orchestrator.md §6.2, UC-04 4a, 4a-1): risk-service,
 * direction {@code INCOMING}, for the same payment ({@code assessmentId = <paymentId>:INCOMING}). {@code ALLOW} →
 * next {@code SETTLE_ON_US}; the state stays {@code FUNDS_RESERVED}. {@code DENY} → {@code REJECTED} with its reason,
 * {@code REVIEW} → {@code REJECTED FR01}, and fail closed as {@code ASSESS_RISK}: after the second technical failure,
 * or the first that asking again cannot mend, {@code REJECTED FR01}. Each rejection releases the hold next.
 */
public class AssessPayeeRiskAction implements SagaAction {

    /** The first try and one retry, as {@code ASSESS_RISK} (UC-04 2c). */
    static final int ATTEMPTS = 2;

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
        RiskAnswer answer = risk.assess(payment, PaymentDirection.INCOMING, readTimeout.get());
        Instant now = clock.instant();
        return switch (answer) {
            case RiskAnswer.Allow allow -> new StepOutcome.Moved(payment.payeeRiskChecked(), "PAYEE_RISK_ALLOW", null);
            case RiskAnswer.Deny deny -> new StepOutcome.Moved(
                    payment.rejected(deny.reason(), PendingAction.RELEASE_HOLD, now), "PAYEE_RISK_DENY",
                    RejectionCause.BUSINESS);
            case RiskAnswer.Review review -> new StepOutcome.Moved(
                    payment.rejected(ReasonCode.FR01, PendingAction.RELEASE_HOLD, now), "PAYEE_RISK_REVIEW",
                    RejectionCause.BUSINESS);
            case RiskAnswer.Unavailable unavailable ->
                    new StepOutcome.TechnicalFailure(unavailable.detail(), unavailable.retryable());
        };
    }

    @Override
    public Optional<StepOutcome.Moved> giveUp(Payment payment, int attempts, StepOutcome.TechnicalFailure failure) {
        if (failure.retryable() && attempts < ATTEMPTS) {
            return Optional.empty();
        }
        return Optional.of(new StepOutcome.Moved(
                payment.rejected(ReasonCode.FR01, PendingAction.RELEASE_HOLD, clock.instant()),
                "PAYEE_RISK_UNAVAILABLE", RejectionCause.TECHNICAL));
    }
}
