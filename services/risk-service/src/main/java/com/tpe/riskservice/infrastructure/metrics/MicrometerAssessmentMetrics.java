package com.tpe.riskservice.infrastructure.metrics;

import com.tpe.riskservice.application.assessment.AssessmentMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * {@code tpe.risk.assessment.duration} as a Micrometer timer; its SLO buckets are
 * {@code management.metrics.distribution.slo} in application.yml (25 §11).
 */
@Component
class MicrometerAssessmentMetrics implements AssessmentMetrics {

    private final MeterRegistry registry;

    MicrometerAssessmentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Timing start() {
        Timer.Sample sample = Timer.start(registry);
        return (direction, decision) -> sample.stop(Timer.builder("tpe.risk.assessment.duration")
                .description("Fraud assessments by direction and decision, service-side")
                .tag("direction", direction.name())
                .tag("decision", decision.name())
                .register(registry));
    }
}
