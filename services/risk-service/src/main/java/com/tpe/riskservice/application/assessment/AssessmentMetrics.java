package com.tpe.riskservice.application.assessment;

import com.tpe.riskservice.domain.rules.Decision;
import com.tpe.riskservice.domain.rules.PaymentDirection;

/** The timer {@code tpe.risk.assessment.duration{direction,decision}} (specs/14-risk-service.md §8, SLO-RSK-02). */
public interface AssessmentMetrics {

    /** Starts timing one assessment; {@link Timing#stop} records it. */
    Timing start();

    interface Timing {

        void stop(PaymentDirection direction, Decision decision);
    }
}
