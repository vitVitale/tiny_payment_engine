package com.tpe.messaging;

import org.springframework.test.context.DynamicPropertyRegistry;
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

    /** For {@code @DynamicPropertySource}: the relay reads {@code spring.kafka.bootstrap-servers} itself. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
}
