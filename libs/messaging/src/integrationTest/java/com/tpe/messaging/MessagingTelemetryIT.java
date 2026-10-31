package com.tpe.messaging;

import com.tpe.messaging.sample.SampleApplication;
import com.tpe.messaging.sample.SampleApplication.Accepting;
import com.tpe.messaging.sample.SampleApplication.Effects;
import com.tpe.observability.test.PrometheusScrape;
import com.tpe.observability.test.TelemetryAssertions;
import com.tpe.paymentdomain.PaymentId;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The common {@code messaging} metrics (specs/00-engineering-baseline.md §11) on the service's Prometheus scrape pass
 * the telemetry contract fixture (26 §4.1): declared in the allow-list, low-cardinality tags only, and the SLO bucket
 * of SLO-MSG-01. Same context as {@link OutboxToInboxIT}.
 */
@SpringBootTest(classes = SampleApplication.class, properties = {
    "tpe.messaging.enabled=true",
    "spring.application.name=sample-service"
})
class MessagingTelemetryIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        MessagingContainers.register(registry);
    }

    @Autowired
    Accepting accepting;

    @Autowired
    Effects effects;

    @Autowired
    PrometheusMeterRegistry prometheus;

    @Test
    void theMessagingMetricsPassTheTelemetryContract() {
        PaymentId payment = new PaymentId(UUID.randomUUID());
        accepting.accept(payment);
        Topics.await(Duration.ofSeconds(20), () -> effects.count(payment) == 1);

        PrometheusScrape scrape = PrometheusScrape.parse(prometheus.scrape());

        TelemetryAssertions.assertThat(scrape)
                .hasMeter("tpe.outbox.pending")
                .hasMeter("tpe.outbox.publish.latency")
                .hasSloBuckets("tpe.outbox.publish.latency")
                .hasMeter("tpe.inbox.duplicates")
                .hasMeter("tpe.relay.active")
                .hasMeterWithTags("tpe.worker.lag", "worker")
                .satisfiesTelemetryContract();
    }
}
