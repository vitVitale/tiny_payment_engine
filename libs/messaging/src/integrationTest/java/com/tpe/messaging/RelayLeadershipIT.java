package com.tpe.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.contracts.ContractJson;
import com.tpe.messaging.outbox.JdbcOutboxRepository;
import com.tpe.messaging.outbox.JdbcOutboxWriter;
import com.tpe.messaging.outbox.OutboxRelay;
import com.tpe.messaging.outbox.OutboxRepository;
import com.tpe.messaging.sample.SampleApplication.Accepting;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.UuidV7Generator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Relay leadership and failure, with relays built by hand as separate instances of one service. Its own database:
 * the cached context of {@link OutboxToInboxIT} keeps relaying the outbox of the shared one.
 */
class RelayLeadershipIT {

    private static DataSource dataSource;
    private final List<OutboxRelay> relays = new ArrayList<>();

    @BeforeAll
    static void migrate() {
        dataSource = MessagingContainers.dataSource("relay_it");
        MessagingContainers.migrate(dataSource);
    }

    @AfterEach
    void stopRelays() {
        relays.forEach(OutboxRelay::stop);
        // rows of this test must not leak into the next one
        new JdbcTemplate(dataSource).update("UPDATE outbox SET published_at = now() WHERE published_at IS NULL");
    }

    @Test
    void onlyTheAdvisoryLockHolderPublishes() {
        String topic = "relay.leadership." + UUID.randomUUID();
        OutboxRelay first = relay(new JdbcOutboxRepository(new JdbcTemplate(dataSource)));
        OutboxRelay second = relay(new JdbcOutboxRepository(new JdbcTemplate(dataSource)));
        Topics.await(Duration.ofSeconds(10), () -> first.isLeader() || second.isLeader());

        List<UUID> written = IntStream.range(0, 20).mapToObj(i -> write(topic)).toList();

        Topics.await(Duration.ofSeconds(20), () -> unpublished(written) == 0);
        OutboxRelay leader = first.isLeader() ? first : second;
        OutboxRelay follower = leader == first ? second : first;
        assertThat(follower.isLeader()).isFalse();
        assertThat(leader.published()).isEqualTo(20);
        assertThat(follower.published()).isZero();
        assertThat(Topics.read(topic, 20)).hasSize(20);
    }

    @Test
    void followerTakesOverWhenTheLeaderStops() {
        String topic = "relay.takeover." + UUID.randomUUID();
        OutboxRelay first = relay(new JdbcOutboxRepository(new JdbcTemplate(dataSource)));
        Topics.await(Duration.ofSeconds(10), first::isLeader);
        OutboxRelay second = relay(new JdbcOutboxRepository(new JdbcTemplate(dataSource)));

        first.stop();
        UUID written = write(topic);

        Topics.await(Duration.ofSeconds(10), () -> unpublished(List.of(written)) == 0);
        assertThat(second.isLeader()).isTrue();
    }

    @Test
    void MSG_AC_02_relayKilledBetweenAckAndUpdatePublishesAgainNeverLoses() {
        String topic = "relay.crash." + UUID.randomUUID();
        CountDownLatch acked = new CountDownLatch(1);
        OutboxRepository crashesBeforeUpdate = new JdbcOutboxRepository(new JdbcTemplate(dataSource)) {
            @Override
            public List<Duration> markPublished(List<Long> ids) {
                acked.countDown(); // every send was acked; the process dies before the update
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("relay killed", e);
                }
                return List.of();
            }
        };
        OutboxRelay crashing = relay(crashesBeforeUpdate);
        UUID eventId = write(topic);
        Topics.await(Duration.ofSeconds(20), () -> acked.getCount() == 0);

        crashing.stop();
        assertThat(unpublished(List.of(eventId))).isEqualTo(1);
        relay(new JdbcOutboxRepository(new JdbcTemplate(dataSource)));

        Topics.await(Duration.ofSeconds(20), () -> unpublished(List.of(eventId)) == 0);
        assertThat(Topics.read(topic, 2))
                .hasSize(2)
                .allSatisfy(record -> assertThat(Topics.header(record, "tpe-event-id")).isEqualTo(eventId.toString()));
    }

    private OutboxRelay relay(OutboxRepository repository) {
        OutboxRelay relay = new OutboxRelay(dataSource, repository,
                OutboxRelay.producerConfig(MessagingContainers.KAFKA.getBootstrapServers()),
                OutboxRelay.Settings.defaults("relay-it"));
        relay.start();
        relays.add(relay);
        return relay;
    }

    private UUID write(String topic) {
        JdbcOutboxWriter writer = new JdbcOutboxWriter(new JdbcTemplate(dataSource), new ContractJson(),
                new UuidV7Generator(Clock.systemUTC(), new SecureRandom()), Clock.systemUTC(), "relay-it");
        PaymentId payment = new PaymentId(UUID.randomUUID());
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource)).execute(status ->
                writer.write(topic, payment.value().toString(), Accepting.message(payment)));
    }

    private int unpublished(List<UUID> eventIds) {
        return new JdbcTemplate(dataSource).queryForObject(
                "SELECT count(*) FROM outbox WHERE published_at IS NULL AND event_id = ANY(?::uuid[])",
                Integer.class, (Object) eventIds.stream().map(UUID::toString).toArray(String[]::new));
    }
}
