package com.tpe.observability.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class ObservabilityEnvironmentPostProcessorTest {

    private final ObservabilityEnvironmentPostProcessor processor = new ObservabilityEnvironmentPostProcessor();

    @Test
    void setsTheLoggingAndMetricsBaseline() {
        var env = new MockEnvironment()
                .withProperty("OTEL_SERVICE_NAME", "csm-gateway")
                .withProperty("OTEL_RESOURCE_ATTRIBUTES", "service.version=2.0.1+abc1234,"
                        + "service.instance.id=csm-gateway-0,deployment.environment.name=prod");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("logging.structured.format.console")).isEqualTo("ecs");
        assertThat(env.getProperty("logging.structured.ecs.service.name")).isEqualTo("csm-gateway");
        assertThat(env.getProperty("logging.structured.ecs.service.version")).isEqualTo("2.0.1+abc1234");
        assertThat(env.getProperty("logging.structured.ecs.service.environment")).isEqualTo("prod");
        assertThat(env.getProperty("logging.structured.ecs.service.node-name")).isEqualTo("csm-gateway-0");
        assertThat(env.getProperty("management.metrics.tags.application")).isEqualTo("csm-gateway");
        assertThat(env.getProperty("management.metrics.tags.version")).isEqualTo("2.0.1+abc1234");
        assertThat(env.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,prometheus");
        assertThat(env.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
        assertThat(env.getProperty("management.metrics.distribution.slo.http.server.requests"))
                .isEqualTo("50ms,100ms,200ms,300ms,500ms,1000ms,2000ms,5000ms,5500ms");
        // ADR-013: metrics are pulled, never pushed over OTLP
        assertThat(env.getProperty("management.otlp.metrics.export.enabled")).isEqualTo("false");
    }

    @Test
    void bodyLoggingAndHeaderCaptureAreOff() {
        var env = new MockEnvironment().withProperty("spring.application.name", "payment-api");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("spring.mvc.log-request-details")).isEqualTo("false");
        assertThat(env.getProperty("management.httpexchanges.recording.enabled")).isEqualTo("false");
    }

    @Test
    void serviceConfigurationWins() {
        var env = new MockEnvironment()
                .withProperty("spring.application.name", "payment-api")
                .withProperty("management.endpoints.web.exposure.include", "health,prometheus,info");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,prometheus,info");
    }

    @Test
    void disabledAddsNothing() {
        var env = new MockEnvironment()
                .withProperty("spring.application.name", "payment-api")
                .withProperty("tpe.observability.enabled", "false");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("logging.structured.format.console")).isNull();
        assertThat(env.getProperty("management.metrics.tags.application")).isNull();
    }
}
