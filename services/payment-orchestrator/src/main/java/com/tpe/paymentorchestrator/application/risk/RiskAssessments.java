package com.tpe.paymentorchestrator.application.risk;

import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentDirection;
import java.time.Duration;

/**
 * risk-service {@code POST /v1/risk/assessments} (specs/14-risk-service.md §3.1). The {@code assessmentId} is
 * {@code <paymentId>:<direction>}, so asking again answers the stored decision.
 */
public interface RiskAssessments {

    /**
     * Never throws: a failure is {@link RiskAnswer.Unavailable}.
     *
     * @param direction the payment's own, or {@code INCOMING} for the payee of an on-us payment (UC-04 4a)
     */
    RiskAnswer assess(Payment payment, PaymentDirection direction, Duration readTimeout);
}
