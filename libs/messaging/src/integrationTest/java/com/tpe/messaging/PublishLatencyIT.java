package com.tpe.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.messaging.outbox.JdbcOutboxRepository;
import com.tpe.messaging.outbox.OutboxRelay;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * MSG-AC-07: p99 of {@code tpe.outbox.publish.latency} (insert → Kafka ack) below 50 ms at 300 inserts/s on the CI
 * Testcontainers setup (SLO-MSG-01). Read like the SLO: the share of samples in the 50 ms bucket. Its own database.
 */
class PublishLatencyIT {

    private static final int RATE = 300;
    private static final Duration RUN = Duration.ofSeconds(20);
    private static final Duration TARGET = Duration.ofMillis(50);

    private static HikariDataSource pool;
    private static DriverManagerDataSource lockConnections;

    @BeforeAll
    static void migrate() {
        var postgres = MessagingContainers.POSTGRES;
        String url = MessagingContainers.database("latency_it");
        lockConnections = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(10);
        pool = new HikariDataSource(config);
        MessagingContainers.migrate(pool);
    }

    @AfterAll
    static void close() {
        pool.close();
    }

    @Test
    void MSG_AC_07_p99PublishLatencyIsBelow50msAt300InsertsPerSecond() throws Exception {
        String topic = "relay.latency." + UUID.randomUUID();
        warmUp(topic);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        meters.config().meterFilter(bucketAt(TARGET));
        OutboxRelay relay = relay(meters);
        Topics.await(Duration.ofSeconds(10), relay::isLeader);
        OutboxLoad load = new OutboxLoad(pool, topic, 10, "latency-it");

        long started = System.nanoTime();
        writeAtRate(load);
        Duration writing = Duration.ofNanos(System.nanoTime() - started);
        int written = load.size();
        try {
            Timer latency = meters.get("tpe.outbox.publish.latency").timer();
            Topics.await(Duration.ofSeconds(30), () -> latency.count() >= written);

            double withinTarget = countWithin(latency, TARGET) / (double) latency.count();
            assertThat(written / (writing.toNanos() / 1e9)).as("achieved insert rate").isGreaterThan(RATE * 0.95);
            assertThat(withinTarget).as("share of publish latencies within %s (max %.1f ms)", TARGET,
                    latency.max(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(0.99);
        } finally {
            relay.stop();
        }
    }

    /** The topic exists and the broker is warm before the measured relay starts. */
    private static void warmUp(String topic) {
        OutboxRelay relay = relay(new SimpleMeterRegistry());
        try {
            OutboxLoad load = new OutboxLoad(pool, topic, 10, "latency-it");
            load.write(50);
            load.assertDeliveredInKeyOrder(Duration.ofSeconds(30));
        } finally {
            relay.stop();
        }
    }

    /** {@link #RATE} rows per second for {@link #RUN}: three writers, one row each every 10 ms. */
    private static void writeAtRate(OutboxLoad load) throws InterruptedException {
        int writers = 3;
        long period = TimeUnit.SECONDS.toMicros(1) * writers / RATE;
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(writers);
        for (int i = 0; i < writers; i++) {
            scheduler.scheduleAtFixedRate(() -> load.write(1), 0, period, TimeUnit.MICROSECONDS);
        }
        Thread.sleep(RUN);
        scheduler.shutdown();
        scheduler.awaitTermination(5, TimeUnit.SECONDS);
    }

    private static OutboxRelay relay(SimpleMeterRegistry meters) {
        OutboxRelay relay = new OutboxRelay(lockConnections, new JdbcOutboxRepository(new JdbcTemplate(pool)),
                OutboxRelay.producerConfig(MessagingContainers.KAFKA.getBootstrapServers()),
                OutboxRelay.Settings.defaults("latency-it"), meters, live -> { });
        relay.start();
        return relay;
    }

    /** A cumulative bucket at {@code boundary} on the publish latency, as the SLO boundaries add in a service. */
    private static MeterFilter bucketAt(Duration boundary) {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (!id.getName().equals("tpe.outbox.publish.latency")) {
                    return config;
                }
                return DistributionStatisticConfig.builder()
                        .serviceLevelObjectives(boundary.toNanos())
                        .expiry(Duration.ofMinutes(10))
                        .bufferLength(1)
                        .build()
                        .merge(config);
            }
        };
    }

    private static double countWithin(Timer timer, Duration boundary) {
        return Arrays.stream(timer.takeSnapshot().histogramCounts())
                .filter(bucket -> bucket.bucket(TimeUnit.NANOSECONDS) == boundary.toNanos())
                .mapToDouble(CountAtBucket::count)
                .findFirst()
                .orElseThrow();
    }
}
