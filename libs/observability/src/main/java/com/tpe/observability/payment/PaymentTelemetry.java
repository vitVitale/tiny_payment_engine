package com.tpe.observability.payment;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;

/**
 * The only way business code puts payment attributes on telemetry (specs/26-observability.md §4, §5.3): sets the
 * span attributes of the current span and the payment MDC keys from {@link PaymentTags}. Its API takes typed
 * identifiers only, so personal data cannot reach a span or a log line through it (§4.4). Without a current span,
 * only the MDC is set. Call it right after the span starts: {@code tpe.payment.elapsed_ms} is now − {@code
 * acceptedAt}, which §5.3 defines from the span's start.
 */
public final class PaymentTelemetry {

    public static final String PAYMENT_ID = "tpe.payment.id";
    public static final String TX_ID = "tpe.tx.id";
    public static final String UETR = "tpe.uetr";
    public static final String DEBTOR_AGENT_BIC = "tpe.debtor_agent.bic";
    public static final String DIRECTION = "tpe.direction";
    public static final String ON_US = "tpe.on_us";
    public static final String ELAPSED_MS = "tpe.payment.elapsed_ms";

    private final Tracer tracer;
    private final Clock clock;

    public PaymentTelemetry(Tracer tracer, Clock clock) {
        this.tracer = tracer;
        this.clock = clock;
    }

    /** Applies {@code tags}; closing the scope restores the MDC as it was. Span attributes stay on the span. */
    public Scope apply(PaymentTags tags) {
        Span span = tracer.currentSpan();
        if (span != null) {
            tagSpan(span, tags);
        }
        Map<String, String> mdc = new LinkedHashMap<>();
        mdc.put(MdcKeys.TX_ID, tags.txId().value());
        if (tags.paymentId() != null) {
            mdc.put(MdcKeys.PAYMENT_ID, tags.paymentId().value().toString());
        }
        if (tags.uetr() != null) {
            mdc.put(MdcKeys.UETR, tags.uetr().value());
        }
        Map<String, String> previous = new LinkedHashMap<>();
        mdc.forEach((key, value) -> {
            previous.put(key, MDC.get(key));
            MDC.put(key, value);
        });
        return () -> previous.forEach((key, value) -> {
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        });
    }

    private void tagSpan(Span span, PaymentTags tags) {
        span.tag(TX_ID, tags.txId().value());
        span.tag(DEBTOR_AGENT_BIC, tags.debtorAgentBic().value());
        if (tags.paymentId() != null) {
            span.tag(PAYMENT_ID, tags.paymentId().value().toString());
        }
        if (tags.uetr() != null) {
            span.tag(UETR, tags.uetr().value());
        }
        if (tags.direction() != null) {
            span.tag(DIRECTION, tags.direction().name());
        }
        if (tags.onUs() != null) {
            span.tag(ON_US, tags.onUs());
        }
        if (tags.acceptedAt() != null) {
            span.tag(ELAPSED_MS, Duration.between(tags.acceptedAt(), clock.instant()).toMillis());
        }
    }

    /** Restores the MDC on close; does not throw. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {

        @Override
        void close();
    }
}
