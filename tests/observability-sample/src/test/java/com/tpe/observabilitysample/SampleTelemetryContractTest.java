package com.tpe.observabilitysample;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.observability.test.PrometheusScrape;
import com.tpe.observability.test.TelemetryAssertions;
import com.tpe.observabilitysample.ObservabilitySampleApplication.Handled;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

/**
 * The sample's telemetry contract (specs/26-observability.md §4.1): the series OBS-AC-02 then finds in Mimir with the
 * same names and labels.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SampleTelemetryContractTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    PrometheusMeterRegistry prometheus;

    @Test
    void aHandledPaymentIsTracedAndItsLatencyHasTheSloBuckets() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/sample/payments"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());

        Handled handled = JsonMapper.builder().build().readValue(response.body(), Handled.class);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(handled.traceId()).hasSize(32);
        TelemetryAssertions.assertThat(PrometheusScrape.parse(prometheus.scrape()))
                .hasMeter(ObservabilitySampleApplication.LATENCY)
                .hasSloBuckets(ObservabilitySampleApplication.LATENCY)
                .satisfiesTelemetryContract();
    }
}
