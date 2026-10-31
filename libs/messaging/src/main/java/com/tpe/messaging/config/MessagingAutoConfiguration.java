package com.tpe.messaging.config;

import com.tpe.contracts.ContractJson;
import com.tpe.messaging.consumer.DltHook;
import com.tpe.messaging.consumer.DltPublisher;
import com.tpe.messaging.consumer.TpeErrorHandler;
import com.tpe.messaging.housekeeping.MessagingHousekeeping;
import com.tpe.messaging.housekeeping.MessagingRetention;
import com.tpe.messaging.inbox.InboxGuard;
import com.tpe.messaging.outbox.JdbcOutboxRepository;
import com.tpe.messaging.outbox.JdbcOutboxWriter;
import com.tpe.messaging.outbox.OutboxRelay;
import com.tpe.messaging.outbox.OutboxRepository;
import com.tpe.messaging.outbox.OutboxWriter;
import com.tpe.paymentdomain.UuidV7Generator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The outbox, relay, inbox and listener error handling of specs/01-shared-libraries.md §3, enabled by
 * {@code tpe.messaging.enabled=true}. The service provides PostgreSQL ({@code spring.datasource.*}) and Kafka
 * ({@code spring.kafka.bootstrap-servers}, read by the relay's and the DLT's own producers); the Kafka listener and
 * Flyway defaults come from {@link MessagingEnvironmentPostProcessor}.
 */
@AutoConfiguration(afterName = {
    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration"
})
@ConditionalOnProperty(name = MessagingEnvironmentPostProcessor.ENABLED, havingValue = "true")
public class MessagingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ContractJson contractJson() {
        return new ContractJson();
    }

    @Bean
    OutboxWriter outboxWriter(JdbcTemplate jdbc, ContractJson json, ObjectProvider<Clock> clock,
            Environment environment) {
        Clock time = clock.getIfAvailable(Clock::systemUTC);
        return new JdbcOutboxWriter(jdbc, json, new UuidV7Generator(time, new SecureRandom()), time,
                environment.getRequiredProperty("spring.application.name"));
    }

    @Bean
    InboxGuard inboxGuard(JdbcTemplate jdbc, ObjectProvider<MeterRegistry> registry) {
        return new InboxGuard(jdbc, registry.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** Its own producer, not a {@code KafkaTemplate} bean, so it leaves the service's Kafka beans alone. */
    @Bean
    DltPublisher dltPublisher(ObjectProvider<MeterRegistry> registry, ObjectProvider<DltHook> hooks,
            Environment environment) {
        return DltPublisher.create(environment.getRequiredProperty("spring.kafka.bootstrap-servers"),
                registry.getIfAvailable(SimpleMeterRegistry::new), hooks.orderedStream().toList());
    }

    /**
     * Spring Boot sets the {@code CommonErrorHandler} bean on its listener container factory. A service must not
     * declare another one: with two, Boot sets neither, and poison messages would no longer reach the DLT.
     */
    @Bean
    TpeErrorHandler tpeErrorHandler(DltPublisher dltPublisher) {
        return new TpeErrorHandler(dltPublisher);
    }

    /** {@code tpe.messaging.outbox.retention} (7 d) and {@code tpe.messaging.inbox.retention} (8 d), 01 §3.1. */
    @Bean
    MessagingRetention messagingRetention(JdbcTemplate jdbc, ObjectProvider<Clock> clock, Environment environment) {
        MessagingRetention.Settings defaults = MessagingRetention.Settings.defaults();
        MessagingRetention.Settings settings = new MessagingRetention.Settings(
                environment.getProperty("tpe.messaging.outbox.retention", Duration.class, defaults.outbox()),
                environment.getProperty("tpe.messaging.inbox.retention", Duration.class, defaults.inbox()),
                defaults.chunk());
        return new MessagingRetention(jdbc, settings, clock.getIfAvailable(Clock::systemUTC));
    }

    /** Started and stopped with the application context: {@code tpe.outbox.pending} and the retention runs. */
    @Bean
    MessagingHousekeeping messagingHousekeeping(OutboxRepository outbox, MessagingRetention retention,
            ObjectProvider<MeterRegistry> registry) {
        return new MessagingHousekeeping(outbox, retention, MessagingHousekeeping.Intervals.defaults(),
                registry.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Bean
    @ConditionalOnMissingBean
    OutboxRepository outboxRepository(JdbcTemplate jdbc) {
        return new JdbcOutboxRepository(jdbc);
    }

    /**
     * Started and stopped with the application context. The lock connection is unpooled when
     * {@code spring.datasource.url} is set (01 §3.3); otherwise it falls back to the service's {@code DataSource}. A
     * stalled publish loop turns the application's liveness state {@code BROKEN}, which
     * {@code /actuator/health/liveness} reports, and {@code CORRECT} again once the loop moves.
     */
    @Bean
    OutboxRelay outboxRelay(DataSource dataSource, OutboxRepository repository, ObjectProvider<MeterRegistry> registry,
            ApplicationEventPublisher events, Environment environment) {
        OutboxRelay.Settings defaults = OutboxRelay.Settings.defaults(
                environment.getRequiredProperty("spring.application.name"));
        OutboxRelay.Settings settings = new OutboxRelay.Settings(
                defaults.lockName(),
                environment.getProperty("tpe.messaging.outbox.visibility-window", Duration.class,
                        defaults.visibilityWindow()),
                environment.getProperty("tpe.messaging.outbox.batch-size", Integer.class, defaults.batchSize()),
                defaults.pollTimeout(),
                environment.getProperty("tpe.messaging.outbox.takeover-retry", Duration.class,
                        defaults.takeoverRetry()),
                environment.getProperty("tpe.messaging.outbox.lock-session-timeout", Duration.class,
                        defaults.lockSessionTimeout()),
                environment.getProperty("tpe.messaging.outbox.heartbeat-interval", Duration.class,
                        defaults.heartbeatInterval()),
                environment.getProperty("tpe.messaging.outbox.stall-after", Duration.class, defaults.stallAfter()),
                defaults.minBackoff(),
                defaults.maxBackoff(),
                defaults.ackTimeout());
        String url = environment.getProperty("spring.datasource.url");
        DataSource lockConnections = url == null ? dataSource : new DriverManagerDataSource(url,
                environment.getProperty("spring.datasource.username"),
                environment.getProperty("spring.datasource.password"));
        return new OutboxRelay(lockConnections, repository,
                OutboxRelay.producerConfig(environment.getRequiredProperty("spring.kafka.bootstrap-servers")),
                settings, registry.getIfAvailable(SimpleMeterRegistry::new),
                live -> AvailabilityChangeEvent.publish(events, OutboxRelay.class,
                        live ? LivenessState.CORRECT : LivenessState.BROKEN));
    }
}
