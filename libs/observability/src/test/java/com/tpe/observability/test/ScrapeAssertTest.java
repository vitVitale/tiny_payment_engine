package com.tpe.observability.test;

import static com.tpe.observability.test.TelemetryAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ScrapeAssertTest {

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    @Test
    void declaredMeterWithItsTagsPasses() {
        Timer.builder("tpe.payment.stage.duration").tags("direction", "OUTGOING", "stage", "ACCEPTED_TO_SEND")
                .serviceLevelObjectives(Duration.ofMillis(500), Duration.ofSeconds(1))
                .register(registry)
                .record(Duration.ofMillis(120));

        PrometheusScrape scrape = PrometheusScrape.parse(registry.scrape());

        assertThatCode(() -> assertThat(scrape)
                .hasMeter("tpe.payment.stage.duration")
                .hasMeterWithTags("tpe.payment.stage.duration", "direction", "stage")
                .hasBucketAt("tpe.payment.stage.duration", Duration.ofMillis(500))
                .hasBucketAt("tpe.payment.stage.duration", Duration.ofSeconds(1))
                .satisfiesTelemetryContract()).doesNotThrowAnyException();
    }

    @Test
    void missingMeterFails() {
        PrometheusScrape scrape = PrometheusScrape.parse(registry.scrape());

        assertThatThrownBy(() -> assertThat(scrape).hasMeter("tpe.outbox.pending"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("tpe.outbox.pending");
    }

    @Test
    void missingBucketFails() {
        Timer.builder("tpe.payment.duration").tag("direction", "OUTGOING").tag("outcome", "SETTLED")
                .serviceLevelObjectives(Duration.ofSeconds(1)).register(registry).record(Duration.ofMillis(10));

        assertThatThrownBy(() -> assertThat(PrometheusScrape.parse(registry.scrape()))
                .hasBucketAt("tpe.payment.duration", Duration.ofSeconds(3)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("le=3.0");
    }

    @Test
    void tagKeyOutsideTheAllowListFails() {
        Counter.builder("tpe.dlt.messages").tag("topic", "csm.inbound.events").tag("partition", "3")
                .register(registry).increment();

        assertThatThrownBy(() -> assertThat(PrometheusScrape.parse(registry.scrape())).usesOnlyAllowedTags())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("tpe_dlt_messages_total")
                .hasMessageContaining("partition");
    }

    @Test
    void undeclaredTpeMeterFails() {
        Counter.builder("tpe.made.up").register(registry).increment();

        assertThatThrownBy(() -> assertThat(PrometheusScrape.parse(registry.scrape())).usesOnlyAllowedTags())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("tpe_made_up_total");
    }

    @Test
    void highCardinalityTagFailsOnAnyMeter() {
        Counter.builder("http.client.requests.custom").tag("paymentId", "0192f0e4-6b1d-7c3a-9a55-4c1f3b2e9d10")
                .register(registry).increment();

        assertThatThrownBy(() -> assertThat(PrometheusScrape.parse(registry.scrape())).hasNoHighCardinalityTags())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("paymentId");
    }

    @Test
    void highCardinalityTagOnADeclaredMeterFailsTheContract() {
        Counter.builder("tpe.payment.rejected").tags("direction", "OUTGOING", "reason", "AC04", "cause", "BUSINESS",
                "tx_id", "TPE01JAB3C4D5E6F7G8H9J0K1M2NP").register(registry).increment();

        assertThatThrownBy(() -> assertThat(PrometheusScrape.parse(registry.scrape())).satisfiesTelemetryContract())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("tx_id");
    }

    @Test
    void everySeriesCarriesTheCommonTags() {
        registry.config().commonTags("application", "payment-orchestrator", "version", "1.4.0+3669eee");
        Counter.builder("tpe.inbox.duplicates").register(registry).increment();

        PrometheusScrape scrape = PrometheusScrape.parse(registry.scrape());

        assertThatCode(() -> assertThat(scrape).carriesCommonTags("payment-orchestrator", "1.4.0+3669eee"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> assertThat(scrape).carriesCommonTags("csm-gateway", "1.4.0+3669eee"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("application");
    }

    @Test
    void parsesEscapedLabelValuesAndExemplars() {
        PrometheusScrape scrape = PrometheusScrape.parse("""
                # HELP tpe_outbox_pending pending rows
                # TYPE tpe_outbox_pending gauge
                tpe_outbox_pending{application="a \\"quoted\\", b",version="1"} 3.0
                tpe_payment_duration_seconds_bucket{direction="OUTGOING",le="1.0"} 7 # {trace_id="abc"} 0.5 1.7e9
                # EOF
                """);

        assertThat(scrape).hasMeterWithTags("tpe.outbox.pending", "application", "version")
                .hasBucketAt("tpe.payment.duration", Duration.ofSeconds(1));
        org.assertj.core.api.Assertions.assertThat(scrape.series().getFirst().labels())
                .containsEntry("application", "a \"quoted\", b");
    }
}
