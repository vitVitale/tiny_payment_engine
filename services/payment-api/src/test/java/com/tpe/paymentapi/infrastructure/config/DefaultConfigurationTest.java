package com.tpe.paymentapi.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * The shipped {@code application.yml} binds as the service binds it (specs/10-payment-api.md §4, §6): the component
 * tests override the client timeouts, so a default Spring cannot read would only show when the jar starts.
 */
class DefaultConfigurationTest {

    @Test
    void theClientTimeoutsOfTheDefaultConfigurationBind() throws IOException {
        var environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        var binder = new Binder(ConfigurationPropertySources.get(environment));

        var orchestrator = binder.bind("tpe.clients.orchestrator",
                PaymentApiConfiguration.OrchestratorProperties.class).get();
        var vop = binder.bind("tpe.clients.vop", PaymentApiConfiguration.VopProperties.class).get();

        assertThat(orchestrator.timeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(orchestrator.statusTimeout()).isEqualTo(Duration.ofMillis(300));
        assertThat(vop.checkTimeout()).isEqualTo(Duration.ofMillis(300));
        assertThat(vop.verifyTimeout()).isEqualTo(Duration.ofMillis(5_500));
    }
}
