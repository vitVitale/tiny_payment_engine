package com.tpe.observability.config;

import java.util.LinkedHashMap;
import java.util.Map;
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
        SloBoundaries.DEFAULTS.forEach((meter, boundaries) ->
                defaults.put("management.metrics.distribution.slo." + meter, SloBoundaries.asProperty(boundaries)));
        // 26 §4.4: no request / response bodies or headers in logs
        defaults.put("spring.mvc.log-request-details", "false");
        defaults.put("management.httpexchanges.recording.enabled", "false");
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE, defaults));
    }

    /** After {@code ConfigDataEnvironmentPostProcessor}, so {@code spring.application.name} is known. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
