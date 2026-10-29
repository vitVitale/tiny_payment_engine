package com.tpe.observability.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ResourceIdentityTest {

    @Test
    void readsTheOpenTelemetryEnvironmentVariables() {
        var env = new MockEnvironment()
                .withProperty("OTEL_SERVICE_NAME", "payment-orchestrator")
                .withProperty("OTEL_RESOURCE_ATTRIBUTES", "service.namespace=tpe,service.version=1.4.0%2B3669eee,"
                        + "service.instance.id=payment-orchestrator-7d9f-x2k4,deployment.environment.name=staging")
                .withProperty("spring.application.name", "ignored");

        assertThat(ResourceIdentity.from(env)).isEqualTo(new ResourceIdentity("payment-orchestrator", "tpe",
                "1.4.0+3669eee", "payment-orchestrator-7d9f-x2k4", "staging"));
    }

    @Test
    void serviceNameAttributeIsUsedWithoutOtelServiceName() {
        var env = new MockEnvironment().withProperty("OTEL_RESOURCE_ATTRIBUTES", "service.name=csm-gateway");

        assertThat(ResourceIdentity.from(env).serviceName()).isEqualTo("csm-gateway");
    }

    @Test
    void localDefaultsWithoutDeploymentVariables() {
        var env = new MockEnvironment()
                .withProperty("spring.application.name", "ledger-service")
                .withProperty("HOSTNAME", "laptop-42");

        assertThat(ResourceIdentity.from(env))
                .isEqualTo(new ResourceIdentity("ledger-service", "tpe", "unknown", "laptop-42", "local"));
    }

    @Test
    void malformedAttributesAreIgnored() {
        var env = new MockEnvironment()
                .withProperty("spring.application.name", "vop-service")
                .withProperty("OTEL_RESOURCE_ATTRIBUTES", " , novalue, =x, deployment.environment.name = perf ");

        assertThat(ResourceIdentity.from(env).environment()).isEqualTo("perf");
    }
}
