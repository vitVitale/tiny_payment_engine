package com.tpe.observabilitysample;

import com.tpe.observability.payment.PaymentTags;
import com.tpe.observability.payment.PaymentTelemetry;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.TxId;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The sample workload of the compose {@code observability} profile (specs/26-observability.md §13, issue #19): one
 * traced request that sets the payment MDC through {@link PaymentTelemetry} for a synthetic payment and logs inside
 * it. Its {@code http.server.requests} histogram has the SLO buckets and an exemplar per bucket. No payments exist in
 * M0, so OBS-AC-01 … -04 are checked against it.
 */
@SpringBootApplication
public class ObservabilitySampleApplication {

    /** The latency histogram the checks look at: Spring MVC's, with the SLO buckets of 25 §11. */
    public static final String LATENCY = "http.server.requests";

    public static void main(String[] args) {
        SpringApplication.run(ObservabilitySampleApplication.class, args);
    }

    /** 00 §6: time comes from an injected {@code Clock}; {@link PaymentTelemetry} uses it too. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** What the check needs to find the request's telemetry in each backend. */
    public record Handled(String traceId, String paymentId) {}

    @RestController
    static class SamplePayments {

        private static final Logger log = LoggerFactory.getLogger(SamplePayments.class);
        private static final Bic DEBTOR_AGENT = new Bic("SMPLDEFFXXX");

        private final PaymentTelemetry telemetry;
        private final Tracer tracer;
        private final Clock clock;

        SamplePayments(PaymentTelemetry telemetry, Tracer tracer, Clock clock) {
            this.telemetry = telemetry;
            this.tracer = tracer;
            this.clock = clock;
        }

        /** A synthetic payment handled inside the request's server span. */
        @PostMapping("/sample/payments")
        Handled handle() {
            PaymentId payment = new PaymentId(UUID.randomUUID());
            TxId txId = new TxId("SMPL" + ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
            try (var scope = telemetry.apply(PaymentTags.of(txId, DEBTOR_AGENT).paymentId(payment)
                    .acceptedAt(clock.instant()))) {
                log.info("Sample payment handled");
            }
            Span span = tracer.currentSpan();
            return new Handled(span == null ? "" : span.context().traceId(), payment.value().toString());
        }
    }
}
