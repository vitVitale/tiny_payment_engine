package com.tpe.csmgateway.infrastructure.metrics;

import com.tpe.csmgateway.application.GatewayMetrics;
import com.tpe.csmgateway.application.journal.MessageJournal.Direction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** {@link GatewayMetrics} as Micrometer counters (specs/15-csm-gateway.md §8, specs/25-service-levels.md §11). */
@Component
class MicrometerGatewayMetrics implements GatewayMetrics {

    private final MeterRegistry registry;
    private final Counter uncorrelated;
    private final Counter verdictIgnored;
    private final Timer answerMargin;

    MicrometerGatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.uncorrelated = Counter.builder("tpe.csm.uncorrelated")
                .description("pacs.002 that match no transaction")
                .register(registry);
        this.verdictIgnored = Counter.builder("tpe.csm.verdict.ignored")
                .description("Verdicts that decided no answer: late, second, or for no known incoming transfer")
                .register(registry);
        // the 1 s and 3 s boundaries come from SloBoundaries (25 §11, SLO-GW-06); a timer records no negative margin
        this.answerMargin = Timer.builder("tpe.csm.answer.margin")
                .description("Answer deadline minus the moment the answer was decided")
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
    public void answer(String status, String decidedBy) {
        Counter.builder("tpe.csm.answer")
                .description("Answers decided for incoming transfers, by status and what decided them")
                .tag("status", status)
                .tag("decidedBy", decidedBy)
                .register(registry)
                .increment();
    }

    @Override
    public void answerMargin(Duration margin) {
        answerMargin.record(margin);
    }

    @Override
    public void verdictIgnored() {
        verdictIgnored.increment();
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
