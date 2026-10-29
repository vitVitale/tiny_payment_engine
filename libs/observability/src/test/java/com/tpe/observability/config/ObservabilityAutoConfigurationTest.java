package com.tpe.observability.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.config.MeterFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ObservabilityAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class))
            .withPropertyValues("spring.application.name=risk-service");

    @Test
    void onByDefault() {
        runner.run(context -> assertThat(context).hasSingleBean(MeterFilter.class));
    }

    @Test
    void offWhenDisabled() {
        runner.withPropertyValues("tpe.observability.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(MeterFilter.class));
    }
}
