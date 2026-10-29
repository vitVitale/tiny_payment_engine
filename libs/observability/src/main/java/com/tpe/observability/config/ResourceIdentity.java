package com.tpe.observability.config;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.springframework.core.env.PropertyResolver;

/**
 * Who emits a signal (specs/26-observability.md §3): the OTel resource attributes, set once per service instance from
 * the environment variables the deployment provides: {@code OTEL_SERVICE_NAME} and {@code OTEL_RESOURCE_ATTRIBUTES}
 * ({@code key=value} pairs, values percent-encoded). Without them, the service name is
 * {@code spring.application.name} and the rest are local defaults.
 *
 * @param serviceName {@code service.name}, the Prometheus {@code application} tag
 * @param serviceNamespace {@code service.namespace}, {@code tpe}
 * @param serviceVersion {@code service.version}, release version + commit SHA; the Prometheus {@code version} tag
 * @param serviceInstanceId {@code service.instance.id}, the pod name
 * @param environment {@code deployment.environment.name}: {@code local}, {@code systemtest}, {@code perf}, …
 */
public record ResourceIdentity(
        String serviceName, String serviceNamespace, String serviceVersion, String serviceInstanceId,
        String environment) {

    static final String UNKNOWN = "unknown";

    /** The identity of this instance from {@code env}, which includes the process environment variables. */
    public static ResourceIdentity from(PropertyResolver env) {
        Map<String, String> attributes = parse(env.getProperty("OTEL_RESOURCE_ATTRIBUTES", ""));
        String name = env.getProperty("OTEL_SERVICE_NAME",
                attributes.getOrDefault("service.name", env.getProperty("spring.application.name", UNKNOWN)));
        return new ResourceIdentity(
                name,
                attributes.getOrDefault("service.namespace", "tpe"),
                attributes.getOrDefault("service.version", UNKNOWN),
                attributes.getOrDefault("service.instance.id", env.getProperty("HOSTNAME", UNKNOWN)),
                attributes.getOrDefault("deployment.environment.name", "local"));
    }

    /** {@code k1=v1,k2=v2}; entries without a key or a {@code =} are skipped (OTel SDK behaviour). */
    private static Map<String, String> parse(String attributes) {
        Map<String, String> parsed = new HashMap<>();
        for (String entry : attributes.split(",")) {
            int separator = entry.indexOf('=');
            String key = separator < 0 ? "" : entry.substring(0, separator).strip();
            if (!key.isEmpty()) {
                String value = entry.substring(separator + 1).strip();
                // percent-decoding only: a '+' stays a '+' (1.4.0+3669eee)
                parsed.put(key, URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8));
            }
        }
        return parsed;
    }
}
