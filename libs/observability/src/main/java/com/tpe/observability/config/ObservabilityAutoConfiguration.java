package com.tpe.observability.config;

import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * The telemetry beans of specs/26-observability.md §4, on by default ({@code tpe.observability.enabled}). The logging
 * and metrics defaults come from {@link ObservabilityEnvironmentPostProcessor}.
 */
@AutoConfiguration
@ConditionalOnProperty(name = ObservabilityEnvironmentPostProcessor.ENABLED, matchIfMissing = true)
public class ObservabilityAutoConfiguration {

    /**
     * {@code jvm.info} has its own {@code version} tag, the JDK version, which would win over the common tag
     * {@code version}, the release (26 §3). It is kept as {@code jvm.version}. Runs before the common tags are added.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    MeterFilter jvmVersionTag() {
        return MeterFilter.renameTag("jvm.info", "version", "jvm.version");
    }
}
