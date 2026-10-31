package com.tpe.messaging.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * With {@code tpe.messaging.enabled=true}, adds the defaults of specs/01-shared-libraries.md §3 at the lowest
 * precedence: Flyway also runs the library migrations ({@code classpath:db/migration/messaging}, 00 §9) with a
 * session lock instead of a transaction one, so {@code CREATE INDEX CONCURRENTLY} can run, and the
 * Kafka listener container uses the consumer settings of §3.6 (record acks, no auto commit, read committed, 50
 * records per poll). Also: the service name as consumer group (02 §2), and a new group starts at the earliest offset,
 * so it skips nothing already on the topic. Listener concurrency per assigned partitions (§3.6) is not set yet.
 */
public final class MessagingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String ENABLED = "tpe.messaging.enabled";

    static final String PROPERTY_SOURCE = "tpeMessagingDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty(ENABLED, Boolean.class, false)) {
            return;
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("spring.flyway.locations", "classpath:db/migration,classpath:db/migration/messaging");
        // CREATE INDEX CONCURRENTLY (00 §9) waits for every open transaction, Flyway's own lock transaction too
        defaults.put("spring.flyway.postgresql.transactional-lock", "false");
        defaults.put("spring.kafka.listener.ack-mode", "RECORD");
        defaults.put("spring.kafka.consumer.enable-auto-commit", "false");
        defaults.put("spring.kafka.consumer.isolation-level", "read_committed");
        defaults.put("spring.kafka.consumer.max-poll-records", "50");
        defaults.put("spring.kafka.consumer.auto-offset-reset", "earliest");
        defaults.put("spring.kafka.consumer.group-id", "${spring.application.name}");
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE, defaults));
    }

    /** After the service's config files, like the observability defaults. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
