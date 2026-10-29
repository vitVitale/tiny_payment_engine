package com.tpe.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.messaging.outbox.JdbcOutboxRepository;
import com.tpe.messaging.outbox.OutboxRelay;
import com.tpe.messaging.sample.SampleApplication;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * MSG-AC-08, a blocked publish loop: the service instance (a Spring context) gives up the lock within 5 s, another
 * instance (a relay built by hand) takes over, and {@code /actuator/health/liveness} reports the instance down. Its
 * own database: the cached context of {@link OutboxToInboxIT} keeps relaying the shared one.
 */
class RelayLivenessIT {

    private static final String DATABASE = "liveness_it";
    private static final String SERVICE = "liveness-service";

    private static DataSource dataSource;

    private ConfigurableApplicationContext context;
    private OutboxRelay other;

    @BeforeAll
    static void createDatabase() {
        var postgres = MessagingContainers.POSTGRES;
        var admin = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()));
        if (admin.queryForList("SELECT 1 FROM pg_database WHERE datname = ?", DATABASE).isEmpty()) {
            admin.execute("CREATE DATABASE " + DATABASE);
        }
        dataSource = new DriverManagerDataSource(url(), postgres.getUsername(), postgres.getPassword());
    }

    @AfterEach
    void stop() {
        if (context != null) {
            context.getBean(FreezableOutboxRepository.class).thaw();
            context.close();
        }
        if (other != null) {
            other.stop();
        }
    }

    @Test
    void MSG_AC_08_aBlockedPublishLoopHandsOverWithin5sAndReportsNotLive() {
        context = new SpringApplicationBuilder(SampleApplication.class, Blocking.class)
                .properties(
                        "tpe.messaging.enabled=true",
                        "spring.application.name=" + SERVICE,
                        "spring.datasource.url=" + url(),
                        "spring.datasource.username=" + MessagingContainers.POSTGRES.getUsername(),
                        "spring.datasource.password=" + MessagingContainers.POSTGRES.getPassword(),
                        "spring.kafka.bootstrap-servers=" + MessagingContainers.KAFKA.getBootstrapServers(),
                        "spring.kafka.listener.auto-startup=false",
                        "management.endpoint.health.probes.enabled=true")
                .run();
        OutboxRelay relay = context.getBean(OutboxRelay.class);
        HealthEndpoint health = context.getBean(HealthEndpoint.class);
        FreezableOutboxRepository repository = context.getBean(FreezableOutboxRepository.class);
        Topics.await(Duration.ofSeconds(10), relay::isLeader);
        other = new OutboxRelay(dataSource, new JdbcOutboxRepository(new JdbcTemplate(dataSource)),
                OutboxRelay.producerConfig(MessagingContainers.KAFKA.getBootstrapServers()),
                OutboxRelay.Settings.defaults(SERVICE));
        other.start();
        OutboxLoad load = new OutboxLoad(dataSource, "relay.liveness." + UUID.randomUUID(), 10, SERVICE)
                .startWriting(Duration.ofMillis(5));
        assertThat(liveness(health)).isEqualTo(Status.UP);

        repository.freeze();
        repository.awaitHolding(Duration.ofSeconds(5));
        long blocked = System.nanoTime();

        Topics.await(Duration.ofSeconds(5), () -> other.published() > 0);
        assertThat(Duration.ofNanos(System.nanoTime() - blocked)).isLessThan(Duration.ofSeconds(5));
        assertThat(relay.isLeader()).isFalse();
        assertThat(relay.isLive()).isFalse();
        assertThat(liveness(health)).isEqualTo(Status.DOWN);

        repository.thaw();
        Topics.await(Duration.ofSeconds(5), () -> liveness(health) == Status.UP);
        load.assertDeliveredInKeyOrder(Duration.ofSeconds(30));
    }

    private static Status liveness(HealthEndpoint health) {
        return health.healthForPath("liveness").getStatus();
    }

    private static String url() {
        var postgres = MessagingContainers.POSTGRES;
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + DATABASE;
    }

    /** The service's outbox repository, frozen on demand. */
    @Configuration(proxyBeanMethods = false)
    static class Blocking {

        @Bean
        FreezableOutboxRepository outboxRepository(JdbcTemplate jdbc) {
            return new FreezableOutboxRepository(jdbc);
        }
    }
}
