package com.tpe.paymentorchestrator.application.risk;

import com.tpe.paymentorchestrator.domain.payment.Payment;
import java.time.Duration;

/**
 * risk-service {@code POST /v1/risk/assessments} (specs/14-risk-service.md §3.1), in the payment's direction. The
 * {@code assessmentId} is {@code <paymentId>:<direction>}, so asking again answers the stored decision.
 */
public interface RiskAssessments {

    /** Never throws: a failure is {@link RiskAnswer.Unavailable}. */
    RiskAnswer assess(Payment payment, Duration readTimeout);
}
