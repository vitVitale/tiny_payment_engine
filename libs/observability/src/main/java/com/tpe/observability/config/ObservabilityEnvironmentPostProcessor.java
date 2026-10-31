package com.tpe.observability.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Adds the logging and metrics baseline as defaults with the lowest precedence, so a service's own configuration
 * wins (specs/00-engineering-baseline.md §11, specs/26-observability.md §3, §4): ECS JSON logs with the resource
 * identity, the common tags {@code application} and {@code version}, the actuator health probes and Prometheus
 * endpoint, the SLO histogram boundaries, and no request detail logging or HTTP exchange recording. Runs after the
 * service's config files are loaded and before logging starts. Off with {@code tpe.observability.enabled=false}.
 */
public final class ObservabilityEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** The switch of the whole library, default {@code true} (26 §4.5). */
    public static final String ENABLED = "tpe.observability.enabled";

    static final String PROPERTY_SOURCE = "tpeObservabilityDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty(ENABLED, Boolean.class, true)) {
            return;
        }
        ResourceIdentity identity = ResourceIdentity.from(environment);
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("logging.structured.format.console", "ecs");
        defaults.put("logging.structured.ecs.service.name", identity.serviceName());
        defaults.put("logging.structured.ecs.service.version", identity.serviceVersion());
        defaults.put("logging.structured.ecs.service.environment", identity.environment());
        defaults.put("logging.structured.ecs.service.node-name", identity.serviceInstanceId());
        defaults.put("management.metrics.tags." + TpeMeters.APPLICATION, identity.serviceName());
        defaults.put("management.metrics.tags." + TpeMeters.VERSION, identity.serviceVersion());
        defaults.put("management.endpoints.web.exposure.include", "health,prometheus");
        defaults.put("management.endpoint.health.probes.enabled", "true");
        // ADR-013: metrics are pulled from /actuator/prometheus; OpenTelemetry's starter would also push them
        defaults.put("management.otlp.metrics.export.enabled", "false");
        SloBoundaries.DEFAULTS.forEach((meter, boundaries) ->
                defaults.put("management.metrics.distribution.slo." + meter, SloBoundaries.asProperty(boundaries)));
        // 26 §4.4: no request / response bodies or headers in logs
        defaults.put("spring.mvc.log-request-details", "false");
        defaults.put("management.httpexchanges.recording.enabled", "false");
        addTracingDefaults(environment, identity, defaults);
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE, defaults));
    }

    /**
     * 26 §4.2, §4.5, §5.5: every trace sampled, OTLP/gRPC to the node-local agent, a bounded batch processor that
     * drops instead of blocking, the resource attributes of §3, and JDBC spans for connections and queries only,
     * without parameter values.
     */
    private static void addTracingDefaults(ConfigurableEnvironment environment, ResourceIdentity identity,
            Map<String, Object> defaults) {
        defaults.put("management.tracing.sampling.probability", "1.0");
        String export = "management.opentelemetry.tracing.export.";
        defaults.put(export + "max-queue-size", "${tpe.observability.trace.queue-size:8192}");
        defaults.put(export + "max-batch-size", "512");
        defaults.put(export + "schedule-delay", "1s");
        defaults.put(export + "timeout", "${tpe.observability.trace.export-timeout:2s}");
        defaults.put(export + "otlp.transport", "grpc");
        defaults.put(export + "otlp.timeout", "${tpe.observability.trace.export-timeout:2s}");
        otlpEndpoint(environment).ifPresent(endpoint -> defaults.put(export + "otlp.endpoint", endpoint));
        String resource = "management.opentelemetry.resource-attributes";
        defaults.put(resource + "[service.name]", identity.serviceName());
        defaults.put(resource + "[service.namespace]", identity.serviceNamespace());
        defaults.put(resource + "[service.version]", identity.serviceVersion());
        defaults.put(resource + "[service.instance.id]", identity.serviceInstanceId());
        defaults.put(resource + "[deployment.environment.name]", identity.environment());
        // datasource-micrometer: no FETCH spans, no parameter values
        defaults.put("jdbc.includes", "CONNECTION,QUERY");
        defaults.put("jdbc.datasource-proxy.include-parameter-values", "false");
    }

    /**
     * {@code OTEL_EXPORTER_OTLP_ENDPOINT}, else {@code http://$HOST_IP:4317}. Without either (a test, a laptop without
     * the compose stack) there is no OTLP exporter and spans are only kept in process.
     */
    private static Optional<String> otlpEndpoint(ConfigurableEnvironment environment) {
        String endpoint = environment.getProperty("OTEL_EXPORTER_OTLP_ENDPOINT");
        if (endpoint != null && !endpoint.isBlank()) {
            return Optional.of(endpoint);
        }
        String hostIp = environment.getProperty("HOST_IP");
        return hostIp == null || hostIp.isBlank() ? Optional.empty() : Optional.of("http://" + hostIp + ":4317");
    }

    /** After {@code ConfigDataEnvironmentPostProcessor}, so {@code spring.application.name} is known. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
