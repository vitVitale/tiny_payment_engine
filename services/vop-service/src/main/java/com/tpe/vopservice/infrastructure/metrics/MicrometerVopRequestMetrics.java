package com.tpe.vopservice.infrastructure.metrics;

import com.tpe.vopservice.application.metrics.VopRequestMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * {@code tpe.vop.requests} as a Micrometer timer; its SLO buckets are {@code management.metrics.distribution.slo} in
 * application.yml (25 §11).
 */
@Component
class MicrometerVopRequestMetrics implements VopRequestMetrics {

    static final String NAME = "tpe.vop.requests";

    private final MeterRegistry registry;

    MicrometerVopRequestMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Timing start() {
        Timer.Sample sample = Timer.start(registry);
        return (role, route, result, cause) -> sample.stop(Timer.builder(NAME)
                .description("Payee verifications by role, route, result and cause")
                .tag("role", role.name())
                .tag("route", route.name())
                .tag("result", result.name())
                .tag("cause", cause.name())
                .register(registry));
    }
}
