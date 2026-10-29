package com.tpe.messaging.outbox;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Publishes the outbox to Kafka (specs/01-shared-libraries.md §3.3, ADR-006):
 * <ul>
 *   <li>One active relay per service: each instance holds a dedicated connection and tries
 *       {@code pg_try_advisory_lock(hashtext('<service>-outbox'))} on it; only the holder publishes, the others try
 *       again every {@code takeoverRetry}.</li>
 *   <li>The leader waits for {@code NOTIFY tpe_outbox} or the poll timeout, reads the oldest unpublished rows in
 *       {@code id} order, sends them, waits for every ack and only then sets {@code published_at}.</li>
 *   <li>A failed send stops the batch: acked rows before it are marked, the rest are not, and the loop starts again
 *       from the oldest unpublished row after a back-off. Sending a row twice is allowed (consumers deduplicate);
 *       skipping one is not.</li>
 * </ul>
 * The lock connection comes from its own, unpooled {@code DataSource}: a pooled session would keep the lock and the
 * listener when handed out again. Heartbeat on it and stall detection come with bounded failover (issue #16); the
 * metrics {@code tpe.outbox.pending} and {@code tpe.outbox.publish.latency} with issue #17.
 */
public final class OutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Timing and size of the relay; {@link #defaults} are those of 01 §3.3. */
    public record Settings(
            String lockName, Duration visibilityWindow, int batchSize, Duration pollTimeout, Duration takeoverRetry,
            Duration minBackoff, Duration maxBackoff, Duration ackTimeout) {

        /** {@code <service>-outbox}, 20 ms window, batch 200, 100 ms poll, 1 s takeover, back-off 50 ms → 1 s. */
        public static Settings defaults(String service) {
            return new Settings(service + "-outbox", Duration.ofMillis(20), 200, Duration.ofMillis(100),
                    Duration.ofSeconds(1), Duration.ofMillis(50), Duration.ofSeconds(1), Duration.ofSeconds(15));
        }
    }

    private final DataSource lockConnections;
    private final OutboxRepository repository;
    private final Map<String, Object> producerConfig;
    private final Settings settings;
    private final AtomicLong published = new AtomicLong();

    private volatile boolean running;
    private volatile boolean leader;
    private Thread thread;
    private Producer<String, String> producer;

    /**
     * @param lockConnections gives the dedicated lock connection; unpooled (01 §3.3), e.g. a
     *     {@code DriverManagerDataSource}
     */
    public OutboxRelay(DataSource lockConnections, OutboxRepository repository, Map<String, Object> producerConfig,
            Settings settings) {
        this.lockConnections = lockConnections;
        this.repository = repository;
        this.producerConfig = Map.copyOf(producerConfig);
        this.settings = settings;
    }

    /** The producer settings of 01 §3.3 for {@code bootstrapServers}. */
    public static Map<String, Object> producerConfig(String bootstrapServers) {
        return Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5,
                ProducerConfig.LINGER_MS_CONFIG, 0,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000,
                // Kafka requires delivery.timeout.ms >= linger.ms + request.timeout.ms (default 30 s)
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000,
                ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
    }

    /** Whether this instance holds the advisory lock and publishes. */
    public boolean isLeader() {
        return leader;
    }

    /** Rows this instance published and marked since start. */
    public long published() {
        return published.get();
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        producer = new KafkaProducer<>(producerConfig, new StringSerializer(), new StringSerializer());
        running = true;
        thread = Thread.ofPlatform().name("outbox-relay").daemon().start(this::run);
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        thread.interrupt();
        try {
            thread.join(Duration.ofSeconds(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        producer.close(Duration.ofSeconds(2));
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void run() {
        while (running) {
            try (Connection lockConnection = lockConnections.getConnection()) {
                lockConnection.setAutoCommit(true);
                try {
                    while (running) {
                        if (tryLock(lockConnection)) {
                            lead(lockConnection);
                        } else {
                            sleep(settings.takeoverRetry());
                        }
                    }
                } finally {
                    release(lockConnection);
                }
            } catch (SQLException | RuntimeException e) {
                if (running) {
                    log.warn("Outbox relay lost its lock connection, rejoining as follower: {}", e.toString());
                }
            } finally {
                leader = false;
            }
            if (running) {
                sleep(settings.takeoverRetry());
            }
        }
    }

    /** Publishes while running; returns only when stopped. A lost lock connection ends it with an exception. */
    private void lead(Connection lockConnection) throws SQLException {
        leader = true;
        try (Statement listen = lockConnection.createStatement()) {
            listen.execute("LISTEN " + JdbcOutboxWriter.CHANNEL);
        }
        log.info("Outbox relay is the leader for {}", settings.lockName());
        PGConnection notifications = lockConnection.unwrap(PGConnection.class);
        Duration backoff = settings.minBackoff();
        while (running) {
            boolean full;
            try {
                full = publishBatch();
                backoff = settings.minBackoff();
            } catch (PublishFailedException e) {
                log.warn("Outbox send failed, retrying from the oldest unpublished row in {}: {}", backoff,
                        e.getCause().toString());
                sleep(backoff);
                backoff = min(backoff.multipliedBy(2), settings.maxBackoff());
                continue;
            }
            if (!full && running) {
                notifications.getNotifications((int) settings.pollTimeout().toMillis());
            }
        }
    }

    /** Sends the oldest batch; returns whether it was full, so more rows may be waiting. */
    private boolean publishBatch() {
        List<OutboxRow> rows = repository.oldestUnpublished(settings.visibilityWindow(), settings.batchSize());
        if (rows.isEmpty()) {
            return false;
        }
        List<Future<RecordMetadata>> sends = new ArrayList<>();
        RuntimeException sendFailure = null;
        for (OutboxRow row : rows) {
            try {
                sends.add(producer.send(record(row)));
            } catch (RuntimeException e) {
                sendFailure = e; // later rows are not sent: they would be marked out of order
                break;
            }
        }
        List<Long> acked = new ArrayList<>();
        Exception failure = sendFailure;
        for (int i = 0; i < sends.size() && failure == null; i++) {
            try {
                sends.get(i).get(settings.ackTimeout().toMillis(), TimeUnit.MILLISECONDS);
                acked.add(rows.get(i).id());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = e;
            } catch (Exception e) {
                failure = e;
            }
        }
        repository.markPublished(acked);
        published.addAndGet(acked.size());
        if (failure != null) {
            throw new PublishFailedException(failure);
        }
        return rows.size() == settings.batchSize();
    }

    private static ProducerRecord<String, String> record(OutboxRow row) {
        ProducerRecord<String, String> record = new ProducerRecord<>(row.topic(), row.key(), row.payload());
        row.headers().forEach((name, value) -> record.headers().add(name, value.getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    private boolean tryLock(Connection lockConnection) throws SQLException {
        try (PreparedStatement statement =
                lockConnection.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))")) {
            statement.setString(1, settings.lockName());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    /** Releases the lock and the listener even if the connection is pooled after all. */
    private void release(Connection lockConnection) {
        try (PreparedStatement unlock = lockConnection.prepareStatement("SELECT pg_advisory_unlock_all()");
                Statement unlisten = lockConnection.createStatement()) {
            unlock.execute();
            unlisten.execute("UNLISTEN *");
        } catch (SQLException e) {
            log.debug("Outbox relay could not release its lock connection cleanly", e);
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // stop() interrupts; the loops check running
        }
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    /** A send of the batch failed or was not acked in time. */
    private static final class PublishFailedException extends RuntimeException {

        PublishFailedException(Exception cause) {
            super(cause);
        }
    }
}
