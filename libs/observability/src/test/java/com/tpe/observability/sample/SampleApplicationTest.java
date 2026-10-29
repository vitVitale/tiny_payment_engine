package com.tpe.observability.sample;

import static com.tpe.observability.test.TelemetryAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.observability.config.SloBoundaries;
import com.tpe.observability.config.TpeMeters;
import com.tpe.observability.payment.MdcKeys;
import com.tpe.observability.test.PrometheusScrape;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        classes = SampleApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "OTEL_SERVICE_NAME=sample-service",
            "OTEL_RESOURCE_ATTRIBUTES=service.version=1.2.3+abc,deployment.environment.name=systemtest"
        })
@ExtendWith(OutputCaptureExtension.class)
class SampleApplicationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    int port;

    @Autowired
    MeterRegistry registry;

    @Test
    void exposesTheThreeActuatorEndpoints() throws Exception {
        assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/prometheus").statusCode()).isEqualTo(200);
    }

    @Test
    void logsEcsJsonWithTheMdcKeys(CapturedOutput output) throws Exception {
        get("/sample");

        String line = output.getOut().lines()
                .filter(candidate -> candidate.contains(SampleApplication.LOG_MESSAGE))
                .findFirst()
                .orElseThrow();
        JsonNode json = JsonMapper.builder().build().readTree(line);

        assertThat(json.path("log").path("level").asString()).isEqualTo("INFO");
        assertThat(json.path("service").path("name").asString()).isEqualTo("sample-service");
        assertThat(json.path("service").path("version").asString()).isEqualTo("1.2.3+abc");
        assertThat(json.path("service").path("environment").asString()).isEqualTo("systemtest");
        for (String key : List.of(MdcKeys.PAYMENT_ID, MdcKeys.TX_ID, MdcKeys.UETR, MdcKeys.COMMAND_ID,
                MdcKeys.EVENT_ID, MdcKeys.TRACE_ID, MdcKeys.SPAN_ID)) {
            assertThat(json.has(key)).as(key).isTrue();
        }
    }

    @Test
    void everySeriesCarriesTheCommonTags() throws Exception {
        get("/sample");

        assertThat(scrape()).carriesCommonTags("sample-service", "1.2.3+abc");
    }

    @Test
    void everyConfiguredSloThresholdIsAnExactBucket() throws Exception {
        get("/sample"); // http.server.requests
        SloBoundaries.DEFAULTS.keySet().stream()
                .filter(meter -> meter.startsWith("tpe."))
                .forEach(meter -> Timer.builder(meter)
                        .tags(TpeMeters.tagKeys(meter).stream().flatMap(key -> List.of(key, "x").stream())
                                .toArray(String[]::new))
                        .register(registry)
                        .record(Duration.ofMillis(1)));

        PrometheusScrape scrape = scrape();

        SloBoundaries.DEFAULTS.keySet().forEach(meter -> assertThat(scrape).hasSloBuckets(meter));
        assertThat(scrape).satisfiesTelemetryContract();
    }

    private PrometheusScrape scrape() throws IOException, InterruptedException {
        return PrometheusScrape.parse(get("/actuator/prometheus").body());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        // the Prometheus text format; health answers JSON to any
        String accept = path.equals("/actuator/prometheus") ? "text/plain" : "*/*";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", accept)
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
