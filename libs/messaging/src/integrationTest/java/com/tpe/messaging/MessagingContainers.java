package com.tpe.messaging;

import org.springframework.test.context.DynamicPropertyRegistry;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Network;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL and Kafka of the integration tests, the versions of the compose stack (deploy/docker-compose.yml),
 * started once per JVM and shared by every test class. PostgreSQL is reachable as {@code postgres:5432} on
 * {@link #NETWORK}, for a Toxiproxy in front of it.
 */
public final class MessagingContainers {

    public static final Network NETWORK = Network.newNetwork();
    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
            .withNetwork(NETWORK)
            .withNetworkAliases("postgres");
    public static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    private MessagingContainers() {}

    /**
     * The JDBC URL of database {@code name} on {@link #POSTGRES}, created on first use: for tests whose rows must not
     * meet the relays of the cached Spring contexts, which publish the shared database.
     */
    public static synchronized String database(String name) {
        var admin = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        if (admin.queryForList("SELECT 1 FROM pg_database WHERE datname = ?", name).isEmpty()) {
            admin.execute("CREATE DATABASE " + name);
        }
        return POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
    }

    /** Unpooled connections to {@link #database}. */
    public static DriverManagerDataSource dataSource(String name) {
        return new DriverManagerDataSource(database(name), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** The library migrations on {@code dataSource}, as a service runs them (MessagingEnvironmentPostProcessor). */
    public static void migrate(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/messaging")
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .load()
                .migrate();
    }

    /** For {@code @DynamicPropertySource}: the relay reads {@code spring.kafka.bootstrap-servers} itself. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
}
