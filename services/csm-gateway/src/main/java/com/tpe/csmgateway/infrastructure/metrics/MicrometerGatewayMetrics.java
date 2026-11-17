package com.tpe.csmgateway.infrastructure.metrics;

import com.tpe.csmgateway.application.GatewayMetrics;
import com.tpe.csmgateway.application.journal.MessageJournal.Direction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** {@link GatewayMetrics} as Micrometer counters (specs/15-csm-gateway.md §8, specs/25-service-levels.md §11). */
@Component
class MicrometerGatewayMetrics implements GatewayMetrics {

    private final MeterRegistry registry;
    private final Counter uncorrelated;

    MicrometerGatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.uncorrelated = Counter.builder("tpe.csm.uncorrelated")
                .description("pacs.002 that match no transaction")
                .register(registry);
    }

    @Override
    public void message(Direction direction, String type) {
        Counter.builder("tpe.csm.messages")
                .description("ISO 20022 messages journalled, by direction and message version")
                .tag("direction", direction.name())
                .tag("type", type)
                .register(registry)
                .increment();
    }

    @Override
    public void notSent(String reason) {
        Counter.builder("tpe.csm.notsent")
                .description("Outgoing transfers decided NOT_SENT, by reason")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    @Override
    public void transmitAttempt(String type) {
        Counter.builder("tpe.csm.transmit.attempts")
                .description("Transmission attempts to the CSM, by message version")
                .tag("type", type)
                .register(registry)
                .increment();
    }

    @Override
    public void uncorrelated() {
        uncorrelated.increment();
    }
}
