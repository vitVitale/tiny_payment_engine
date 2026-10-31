package com.tpe.messaging.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class MessagingEnvironmentPostProcessorTest {

    private final MessagingEnvironmentPostProcessor processor = new MessagingEnvironmentPostProcessor();

    @Test
    void enabledAddsTheLibraryMigrationsAndTheConsumerSettings() {
        var env = new MockEnvironment()
                .withProperty("tpe.messaging.enabled", "true")
                .withProperty("spring.application.name", "csm-gateway");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("spring.flyway.locations"))
                .isEqualTo("classpath:db/migration,classpath:db/migration/messaging");
        assertThat(env.getProperty("spring.flyway.postgresql.transactional-lock")).isEqualTo("false");
        assertThat(env.getProperty("spring.kafka.listener.ack-mode")).isEqualTo("RECORD");
        assertThat(env.getProperty("spring.kafka.consumer.enable-auto-commit")).isEqualTo("false");
        assertThat(env.getProperty("spring.kafka.consumer.isolation-level")).isEqualTo("read_committed");
        assertThat(env.getProperty("spring.kafka.consumer.max-poll-records")).isEqualTo("50");
        assertThat(env.getProperty("spring.kafka.consumer.group-id")).isEqualTo("csm-gateway");
    }

    @Test
    void serviceConfigurationWins() {
        var env = new MockEnvironment()
                .withProperty("tpe.messaging.enabled", "true")
                .withProperty("spring.flyway.locations", "classpath:db/migration,classpath:db/migration/messaging,"
                        + "classpath:db/extra");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("spring.flyway.locations")).endsWith("classpath:db/extra");
    }

    @Test
    void offByDefault() {
        var env = new MockEnvironment();

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("spring.kafka.listener.ack-mode")).isNull();
    }
}
