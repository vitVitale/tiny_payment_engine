package com.tpe.messaging.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Publishes the outbox to Kafka (specs/01-shared-libraries.md §3.3, ADR-006 and its amendment):
 * <ul>
 *   <li>One active relay per service: each instance holds a dedicated {@link LockSession} and tries
 *       {@code pg_try_advisory_lock(hashtext('<service>-outbox'))} on it; only the holder publishes, the others try
 *       again every {@code takeoverRetry}.</li>
 *   <li>The leader waits for {@code NOTIFY tpe_outbox} or the poll timeout, reads the oldest unpublished rows in
 *       {@code id} order, sends them, waits for every ack and only then sets {@code published_at}.</li>
 *   <li>A failed send stops the batch: acked rows before it are marked, the rest are not, and the loop starts again
 *       from the oldest unpublished row after a back-off. Sending a row twice is allowed (consumers deduplicate);
 *       skipping one is not.</li>
 *   <li>Bounded failover: the lock session ends on the server once silent for {@code lockSessionTimeout}. A lease
 *       thread sends a heartbeat on it every {@code heartbeatInterval}, but only while the publish loop moved within
 *       {@code stallAfter}; a stalled loop gets no heartbeat, its lock connection is closed and the instance reports
 *       not live. Before each batch the loop checks the {@link LeaderLease}: after a failed heartbeat, a stall or
 *       when the last good heartbeat is as old as {@code lockSessionTimeout} (the JVM was frozen and the server has
 *       ended the session) it stops publishing and rejoins as a follower on a new connection. A loop iteration
 *       that ends in a failed send counts as moving, and so does waiting for Kafka acks: a Kafka outage must not
 *       make leadership flap.</li>
 * </ul>
 * The lock connection comes from its own, unpooled {@code DataSource}: a pooled session would keep the lock and the
 * listener when handed out again. Gauge {@code tpe.relay.active} is 1 while this instance leads; the metrics
 * {@code tpe.outbox.pending} and {@code tpe.outbox.publish.latency} come with issue #17.
 */
public final class OutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Timing and size of the relay; {@link #defaults} are those of 01 §3.3. */
    public record Settings(
            String lockName, Duration visibilityWindow, int batchSize, Duration pollTimeout, Duration takeoverRetry,
            Duration lockSessionTimeout, Duration heartbeatInterval, Duration stallAfter,
            Duration minBackoff, Duration maxBackoff, Duration ackTimeout) {

        /**
         * The lock session would end between two statements of a follower or a leader otherwise: {@code takeoverRetry}
         * and {@code heartbeatInterval} must stay below {@code lockSessionTimeout}.
         */
        public Settings {
            if (takeoverRetry.compareTo(lockSessionTimeout) >= 0
                    || heartbeatInterval.compareTo(lockSessionTimeout) >= 0) {
                throw new IllegalArgumentException("takeover-retry (" + takeoverRetry + ") and heartbeat-interval ("
                        + heartbeatInterval + ") must be below lock-session-timeout (" + lockSessionTimeout + ")");
            }
        }

        /**
         * {@code <service>-outbox}, 20 ms window, batch 200, 100 ms poll, 1 s takeover, 3 s lock session timeout,
         * 1 s heartbeat, stalled after 2 s, back-off 50 ms → 1 s.
         */
        public static Settings defaults(String service) {
            return new Settings(service + "-outbox", Duration.ofMillis(20), 200, Duration.ofMillis(100),
                    Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(1), Duration.ofSeconds(2),
                    Duration.ofMillis(50), Duration.ofSeconds(1), Duration.ofSeconds(15));
        }
    }

    private final DataSource lockConnections;
    private final OutboxRepository repository;
    private final Map<String, Object> producerConfig;
    private final Settings settings;
    private final Consumer<Boolean> liveness;
    private final LeaderLease lease;
    private final AtomicLong published = new AtomicLong();

    private volatile boolean running;
    private volatile boolean live = true;
    private volatile LockSession session;
    private Thread thread;
    private Thread leaseThread;
    private Producer<String, String> producer;

    /**
     * @param lockConnections gives the dedicated lock connection; unpooled (01 §3.3), e.g. a
     *     {@code DriverManagerDataSource}
     */
    public OutboxRelay(DataSource lockConnections, OutboxRepository repository, Map<String, Object> producerConfig,
            Settings settings) {
        this(lockConnections, repository, producerConfig, settings, new SimpleMeterRegistry(), live -> { });
    }

    /**
     * @param registry gets the gauge {@code tpe.relay.active}
     * @param liveness told when this instance turns not live (a stalled publish loop) and live again
     */
    public OutboxRelay(DataSource lockConnections, OutboxRepository repository, Map<String, Object> producerConfig,
            Settings settings, MeterRegistry registry, Consumer<Boolean> liveness) {
        this.lockConnections = lockConnections;
        this.repository = repository;
        this.producerConfig = Map.copyOf(producerConfig);
        this.settings = settings;
        this.liveness = liveness;
        this.lease = new LeaderLease(settings.stallAfter(), settings.lockSessionTimeout(), System::nanoTime);
        Gauge.builder("tpe.relay.active", this, relay -> relay.isLeader() ? 1 : 0)
                .description("1 while this instance holds the outbox relay lock and publishes")
                .register(registry);
    }

    /**
     * The producer settings of 01 §3.3 for {@code bootstrapServers}. {@code max.block.ms} stays below
     * {@code stallAfter}: a send blocked on missing metadata fails and backs off instead of stalling the loop.
     */
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
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 1_000,
                ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
    }

    /** Whether this instance holds the advisory lock and a valid lease, so it publishes. */
    public boolean isLeader() {
        return lease.isLeader();
    }

    /** False while the publish loop is stalled (ADR-006 amendment); a follower is live. */
    public boolean isLive() {
        return live;
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
        leaseThread = Thread.ofPlatform().name("outbox-relay-lease").daemon().start(this::watch);
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        leaseThread.interrupt();
        thread.interrupt();
        try {
            leaseThread.join(Duration.ofSeconds(5));
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
            try (LockSession lockSession = LockSession.open(lockConnections, settings.lockSessionTimeout())) {
                session = lockSession;
                while (running) {
                    if (lockSession.tryLock(settings.lockName())) {
                        lead(lockSession);
                    } else {
                        sleep(settings.takeoverRetry());
                    }
                }
            } catch (LeadershipLostException e) {
                log.warn("Outbox relay lost its leadership, rejoining as follower on a new connection");
            } catch (SQLException | RuntimeException e) {
                if (running) {
                    log.warn("Outbox relay lost its lock connection, rejoining as follower: {}", e.toString());
                }
            } finally {
                session = null;
                lease.end();
            }
            if (running) {
                sleep(settings.takeoverRetry());
            }
        }
    }

    /**
     * Publishes while running; returns only when stopped. A lost lock connection ends it with an exception, a lost
     * lease with {@link LeadershipLostException}.
     */
    private void lead(LockSession lockSession) throws SQLException {
        lockSession.listen(JdbcOutboxWriter.CHANNEL);
        lease.begin();
        log.info("Outbox relay is the leader for {}", settings.lockName());
        Duration backoff = settings.minBackoff();
        while (running) {
            if (!lease.isLeader()) {
                throw new LeadershipLostException();
            }
            boolean full;
            try {
                full = publishBatch();
                backoff = settings.minBackoff();
            } catch (PublishFailedException e) {
                log.warn("Outbox send failed, retrying from the oldest unpublished row in {}: {}", backoff,
                        e.getCause().toString());
                sleep(backoff);
                backoff = min(backoff.multipliedBy(2), settings.maxBackoff());
                lease.progress();
                continue;
            }
            if (!full && running) {
                lockSession.awaitNotifications(settings.pollTimeout());
            }
            lease.progress();
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
                awaitAck(sends.get(i));
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

    /** Waits up to {@code ackTimeout}, in slices of a heartbeat interval: the loop is waiting, not stuck. */
    private void awaitAck(Future<RecordMetadata> send)
            throws InterruptedException, ExecutionException, TimeoutException {
        long deadline = System.nanoTime() + settings.ackTimeout().toNanos();
        while (true) {
            long slice = Math.min(settings.heartbeatInterval().toNanos(), deadline - System.nanoTime());
            try {
                send.get(Math.max(slice, 0), TimeUnit.NANOSECONDS);
                return;
            } catch (TimeoutException e) {
                if (System.nanoTime() >= deadline) {
                    throw e;
                }
                lease.progress();
            }
        }
    }

    /**
     * The lease thread: every {@code heartbeatInterval}, revokes a stalled leader's lease and closes its lock
     * connection, or else sends the leader's heartbeat; then reports liveness changes.
     */
    private void watch() {
        while (running) {
            sleep(settings.heartbeatInterval());
            long term = lease.term();
            LockSession lockSession = session;
            if (lease.isStalled()) {
                if (lease.revoke(term)) {
                    log.error("Outbox relay publish loop stalled for over {}, giving up the lock",
                            settings.stallAfter());
                    if (lockSession != null) {
                        lockSession.abort();
                    }
                }
            } else if (lease.isLeader() && lockSession != null) {
                heartbeat(lockSession, term);
            }
            reportLiveness();
        }
    }

    /** {@code term} tags the outcome: a late answer on an earlier term's connection does not end the current one. */
    private void heartbeat(LockSession lockSession, long term) {
        try {
            lockSession.heartbeat();
            lease.heartbeatSucceeded(term);
        } catch (SQLException e) {
            lease.heartbeatFailed(term);
            if (running) {
                log.warn("Outbox relay heartbeat failed, stopping before the next batch: {}", e.toString());
            }
        }
    }

    private void reportLiveness() {
        boolean now = lease.isLive();
        if (now != live) {
            live = now;
            liveness.accept(now);
        }
    }

    private static ProducerRecord<String, String> record(OutboxRow row) {
        ProducerRecord<String, String> record = new ProducerRecord<>(row.topic(), row.key(), row.payload());
        row.headers().forEach((name, value) -> record.headers().add(name, value.getBytes(StandardCharsets.UTF_8)));
        return record;
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

    /** The lease ended (failed heartbeat or stall): this instance must not publish another batch. */
    private static final class LeadershipLostException extends RuntimeException {

        LeadershipLostException() {
            super(null, null, false, false);
        }
    }
}
