package com.tpe.csmgateway;

import static com.tpe.csmgateway.Commands.sendCreditTransfer;
import static org.awaitility.Awaitility.await;

import com.tpe.observability.test.PrometheusScrape;
import com.tpe.observability.test.TelemetryAssertions;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The gateway's telemetry contract (specs/20-testing-strategy.md §1, specs/15-csm-gateway.md §8): after a transfer
 * sent and one not sent, the scrape has {@code tpe.csm.messages{direction,type}} and {@code tpe.csm.notsent{reason}}
 * ({@code tpe.csm.uncorrelated} is registered at start, StatusReportIT counts it), every series on the allow-list and
 * no high-cardinality tag.
 */
class TelemetryContractIT extends GatewayStack {

    private static final Duration HANDLED = Duration.ofSeconds(15);

    @Autowired
    private PrometheusMeterRegistry prometheus;

    @Test
    void theGatewaysMetersAreScrapedWithTheirTags() {
        publishCommand("TX-TEL-1", sendCreditTransfer("TX-TEL-1", START.minusSeconds(1)), UUID.randomUUID());
        publishCommand("TX-TEL-2", sendCreditTransfer("TX-TEL-2", START.minusSeconds(10)), UUID.randomUUID());
        await().atMost(HANDLED).until(() -> sentToCsm().size() == 1 && !events("TX-TEL-2").isEmpty());

        TelemetryAssertions.assertThat(PrometheusScrape.parse(prometheus.scrape()))
                .hasMeterWithTags("tpe.csm.messages", "direction", "type")
                .hasMeterWithTags("tpe.csm.notsent", "reason")
                .hasMeter("tpe.csm.uncorrelated")
                .satisfiesTelemetryContract();
    }
}
