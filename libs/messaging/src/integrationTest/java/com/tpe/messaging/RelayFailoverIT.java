package com.tpe.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.messaging.outbox.JdbcOutboxRepository;
import com.tpe.messaging.outbox.OutboxRelay;
import com.tpe.messaging.outbox.OutboxRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

/**
 * Per-key order with two instances and bounded failover (ADR-006 amendment, MSG-AC-03, MSG-AC-08), with relays
 * built by hand as instances of one service. Instance A reaches PostgreSQL through Toxiproxy, so its DB traffic can
 * be blackholed: no packet in or out, the connections stay open, as for a lost node, a partition or a paused
 * container. Its own database, like {@link RelayLeadershipIT}.
 */
class RelayFailoverIT {

    private static final String DATABASE = "failover_it";
    private static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.12.0")
            .withNetwork(MessagingContainers.NETWORK);

    private static HikariDataSource direct;
    private static Proxy proxy;

    private final List<OutboxRelay> relays = new ArrayList<>();
    private final List<HikariDataSource> pools = new ArrayList<>();
    private final List<FreezableOutboxRepository> frozen = new ArrayList<>();
    private final String topic = "relay.failover." + UUID.randomUUID();

    @BeforeAll
    static void migrate() throws IOException {
        var postgres = MessagingContainers.POSTGRES;
        var admin = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()));
        if (admin.queryForList("SELECT 1 FROM pg_database WHERE datname = ?", DATABASE).isEmpty()) {
            admin.execute("CREATE DATABASE " + DATABASE);
        }
        direct = pool(url(postgres.getHost(), postgres.getMappedPort(5432)));
        Flyway.configure().dataSource(direct).locations("classpath:db/migration/messaging").load().migrate();

        TOXIPROXY.start();
        proxy = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort())
                .createProxy("postgres-a", "0.0.0.0:8666", "postgres:5432");
    }

    @AfterEach
    void cleanUp() throws IOException {
        heal();
        frozen.forEach(FreezableOutboxRepository::thaw);
        relays.forEach(OutboxRelay::stop);
        pools.forEach(HikariDataSource::close);
        // rows of this test must not leak into the next one
        new JdbcTemplate(direct).update("UPDATE outbox SET published_at = now() WHERE published_at IS NULL");
    }

    @Test
    void MSG_AC_03_withTwoInstancesEveryKeyArrivesInInsertOrder() {
        SimpleMeterRegistry first = new SimpleMeterRegistry();
        SimpleMeterRegistry second = new SimpleMeterRegistry();
        relay(lockConnections(direct()), new JdbcOutboxRepository(new JdbcTemplate(direct)), first);
        relay(lockConnections(direct()), new JdbcOutboxRepository(new JdbcTemplate(direct)), second);
        OutboxLoad load = new OutboxLoad(direct, topic, 10, "failover-it");

        load.write(1_000);

        load.assertDeliveredInKeyOrder(Duration.ofSeconds(30));
        assertThat(active(first) + active(second)).isEqualTo(1);
    }

    @Test
    void MSG_AC_08_blackholingTheLeadersDbTrafficHandsOverWithin5sAndTheOldLeaderStops() throws IOException {
        DataSource proxied = proxied();
        SimpleMeterRegistry metersA = new SimpleMeterRegistry();
        SimpleMeterRegistry metersB = new SimpleMeterRegistry();
        OutboxRelay a = relay(lockConnections(proxied), new JdbcOutboxRepository(new JdbcTemplate(pool(proxied))),
                metersA);
        Topics.await(Duration.ofSeconds(10), a::isLeader);
        OutboxRelay b = relay(lockConnections(direct()), new JdbcOutboxRepository(new JdbcTemplate(direct)), metersB);
        OutboxLoad load = new OutboxLoad(direct, topic, 10, "failover-it").startWriting(Duration.ofMillis(5));
        Topics.await(Duration.ofSeconds(10), () -> a.published() > 100);

        long blackholed = System.nanoTime();
        blackhole();
        Topics.await(Duration.ofSeconds(5), () -> b.published() > 0);
        assertThat(Duration.ofNanos(System.nanoTime() - blackholed)).isLessThan(Duration.ofSeconds(5));
        pause(Duration.ofSeconds(5));

        heal();
        long publishedByA = a.published();
        pause(Duration.ofSeconds(3));
        assertThat(a.isLeader()).isFalse();
        assertThat(a.published()).isEqualTo(publishedByA);
        assertThat(b.isLeader()).isTrue();
        assertThat(active(metersA)).isZero();
        assertThat(active(metersB)).isEqualTo(1);
        load.assertDeliveredInKeyOrder(Duration.ofSeconds(30));
    }

    @Test
    void MSG_AC_08_aFrozenLeaderWakingWithABatchInFlightOnlyAddsDuplicates() throws IOException {
        DataSource proxied = proxied();
        FreezableOutboxRepository repository = freezable(pool(proxied));
        SimpleMeterRegistry metersA = new SimpleMeterRegistry();
        SimpleMeterRegistry metersB = new SimpleMeterRegistry();
        OutboxRelay a = relay(lockConnections(proxied), repository, metersA);
        Topics.await(Duration.ofSeconds(10), a::isLeader);
        OutboxRelay b = relay(lockConnections(direct()), new JdbcOutboxRepository(new JdbcTemplate(direct)), metersB);
        OutboxLoad load = new OutboxLoad(direct, topic, 10, "failover-it").startWriting(Duration.ofMillis(5));
        Topics.await(Duration.ofSeconds(10), () -> a.published() > 100);

        // frozen: the loop holds the batch it read, nothing reaches the DB, as for a paused container
        repository.freeze();
        repository.awaitHolding(Duration.ofSeconds(5));
        long frozenAt = System.nanoTime();
        blackhole();
        Topics.await(Duration.ofSeconds(5), () -> b.published() > 0);
        assertThat(Duration.ofNanos(System.nanoTime() - frozenAt)).isLessThan(Duration.ofSeconds(5));
        pause(Duration.ofSeconds(3));

        // woken: the old leader sends the batch it held (duplicates of what b sent), then stops
        heal();
        int reads = repository.reads();
        repository.thaw();
        pause(Duration.ofSeconds(3));
        assertThat(repository.reads()).isEqualTo(reads);
        assertThat(a.isLeader()).isFalse();
        assertThat(b.isLeader()).isTrue();
        assertThat(active(metersA)).isZero();
        assertThat(active(metersB)).isEqualTo(1);
        load.assertDeliveredInKeyOrder(Duration.ofSeconds(30));
    }

    private OutboxRelay relay(DataSource lockConnections, OutboxRepository repository, MeterRegistry registry) {
        OutboxRelay relay = new OutboxRelay(lockConnections, repository,
                OutboxRelay.producerConfig(MessagingContainers.KAFKA.getBootstrapServers()),
                OutboxRelay.Settings.defaults("failover-it"), registry, live -> { });
        relay.start();
        relays.add(relay);
        return relay;
    }

    private FreezableOutboxRepository freezable(DataSource dataSource) {
        FreezableOutboxRepository repository = new FreezableOutboxRepository(new JdbcTemplate(dataSource));
        frozen.add(repository);
        return repository;
    }

    /** The pool of an instance's business connections; a read on a blackholed one fails after 5 s. */
    private HikariDataSource pool(DataSource connections) {
        HikariDataSource pool = pool(((DriverManagerDataSource) connections).getUrl());
        pools.add(pool);
        return pool;
    }

    private static HikariDataSource pool(String url) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url + "?socketTimeout=5");
        config.setUsername(MessagingContainers.POSTGRES.getUsername());
        config.setPassword(MessagingContainers.POSTGRES.getPassword());
        config.setMaximumPoolSize(4);
        config.setConnectionTimeout(2_000);
        return new HikariDataSource(config);
    }

    private static DataSource direct() {
        var postgres = MessagingContainers.POSTGRES;
        return lockConnections(new DriverManagerDataSource(url(postgres.getHost(), postgres.getMappedPort(5432))));
    }

    private static DataSource proxied() {
        return new DriverManagerDataSource(url(TOXIPROXY.getHost(), TOXIPROXY.getMappedPort(8666)));
    }

    /** Unpooled, as the relay's lock connection must be (01 §3.3). */
    private static DataSource lockConnections(DataSource connections) {
        var dataSource = (DriverManagerDataSource) connections;
        dataSource.setUsername(MessagingContainers.POSTGRES.getUsername());
        dataSource.setPassword(MessagingContainers.POSTGRES.getPassword());
        return dataSource;
    }

    private static String url(String host, int port) {
        return "jdbc:postgresql://" + host + ":" + port + "/" + DATABASE;
    }

    private static void blackhole() throws IOException {
        proxy.toxics().timeout("blackhole-up", ToxicDirection.UPSTREAM, 0);
        proxy.toxics().timeout("blackhole-down", ToxicDirection.DOWNSTREAM, 0);
    }

    private static void heal() throws IOException {
        for (var toxic : proxy.toxics().getAll()) {
            toxic.remove();
        }
    }

    private static double active(MeterRegistry registry) {
        return registry.get("tpe.relay.active").gauge().value();
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
