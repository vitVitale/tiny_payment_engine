package com.tpe.paymentorchestrator.infrastructure.telemetry;

import com.tpe.observability.context.Spans;
import com.tpe.observability.payment.PaymentDirection;
import com.tpe.observability.payment.PaymentTags;
import com.tpe.observability.payment.PaymentTelemetry;
import com.tpe.paymentorchestrator.application.runner.SagaTelemetry;
import com.tpe.paymentorchestrator.application.runner.SagaTelemetry.Result;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import com.tpe.paymentorchestrator.domain.payment.RejectionCause;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

/**
 * {@link SagaTelemetry} with Micrometer: {@code payment.saga.action} spans as children of the payment's stored
 * {@code traceparent} (ADR-014, specs/26-observability.md §5.2), with the payment attributes of
 * {@link PaymentTelemetry}; {@code tpe.saga.action.duration{action,result}} (specs/11-payment-orchestrator.md §8),
 * {@code tpe.payment.rejected{direction,reason,cause}} and {@code tpe.payment.duration{direction,outcome}}
 * (specs/25-service-levels.md §11).
 */
public class MicrometerSagaTelemetry implements SagaTelemetry {

    /** Span status {@code ERROR} for technical failures only (specs/26-observability.md §5.2). */
    private static final Set<Result> TECHNICAL_FAILURES = EnumSet.of(Result.RETRY, Result.GAVE_UP, Result.ERROR);

    private final Spans spans;
    private final PaymentTelemetry payments;
    private final MeterRegistry registry;

    public MicrometerSagaTelemetry(Spans spans, PaymentTelemetry payments, MeterRegistry registry) {
        this.spans = spans;
        this.payments = payments;
        this.registry = registry;
    }

    @Override
    public ActionSpan actionStarted(Payment payment, PendingAction action, int attempt, boolean resumed) {
        Span span = (payment.traceContext() == null ? spans.newTrace("payment.saga.action")
                : spans.childOf("payment.saga.action", payment.traceContext())).start();
        span.tag("tpe.saga.action", action.name());
        span.tag("tpe.saga.attempt", attempt);
        span.tag("tpe.saga.resumed", resumed);
        Tracer.SpanInScope inScope = spans.inScope(span);
        PaymentTelemetry.Scope mdc = payments.apply(PaymentTags.of(payment.txId(), payment.debtor().agentBic())
                .paymentId(payment.paymentId())
                .uetr(payment.uetr())
                .direction(PaymentDirection.valueOf(payment.direction().name()))
                .onUs(payment.onUs())
                .acceptedAt(payment.acceptedAt()));
        Timer.Sample sample = Timer.start(registry);
        return new ActionSpan() {
            @Override
            public void rejected(Payment rejected, RejectionCause cause) {
                span.tag("tpe.reason", rejected.reasonCode().code());
                span.tag("tpe.cause", cause.name());
            }

            @Override
            public void finished(Result result) {
                sample.stop(Timer.builder("tpe.saga.action.duration")
                        .description("Duration of a saga action: claim to applied outcome")
                        .tag("action", action.name())
                        .tag("result", result.name())
                        .register(registry));
                span.tag("tpe.saga.result", result.name());
                if (TECHNICAL_FAILURES.contains(result)) {
                    span.error(new SagaActionFailure(action, result));
                }
                mdc.close();
                inScope.close();
                span.end();
            }
        };
    }

    /** The error of a span that ended in a technical failure; carries no payment data. */
    private static final class SagaActionFailure extends RuntimeException {

        SagaActionFailure(PendingAction action, Result result) {
            super(action + " " + result, null, false, false);
        }
    }

    @Override
    public void rejected(Payment payment, RejectionCause cause) {
        Counter.builder("tpe.payment.rejected")
                .description("Payments rejected, by reason and by whose failure")
                .tag("direction", payment.direction().name())
                .tag("reason", payment.reasonCode().code())
                .tag("cause", cause.name())
                .register(registry)
                .increment();
    }

    @Override
    public void finalised(Payment payment) {
        Timer.builder("tpe.payment.duration")
                .description("Accepted to final state")
                .tag("direction", payment.direction().name())
                .tag("outcome", payment.state().name())
                .register(registry)
                .record(Duration.between(payment.acceptedAt(), payment.finalisedAt()));
    }
}
