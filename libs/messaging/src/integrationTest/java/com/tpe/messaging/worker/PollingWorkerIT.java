package com.tpe.messaging.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.messaging.MessagingContainers;
import com.tpe.messaging.Topics;
import com.tpe.paymentdomain.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** {@link PollingWorker} against PostgreSQL: the {@code FOR UPDATE SKIP LOCKED} timer loop of 00 §10. */
class PollingWorkerIT {

    private static final String DUE = """
            SELECT id, due_at FROM worker_timers
            WHERE status = 'SCHEDULED' AND due_at <= ?
            ORDER BY due_at
            LIMIT ?
            FOR UPDATE SKIP LOCKED""";
    private static final String OLDEST_DUE =
            "SELECT min(due_at) FROM worker_timers WHERE status = 'SCHEDULED' AND due_at <= ?";

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    private final List<PollingWorker<Timer>> workers = new ArrayList<>();

    record Timer(long id, Instant dueAt) {}

    @BeforeAll
    static void createDatabase() {
        dataSource = MessagingContainers.dataSource("worker_it");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS worker_timers (
                  id          BIGSERIAL PRIMARY KEY,
                  due_at      TIMESTAMPTZ NOT NULL,
                  status      VARCHAR(10) NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED', 'FIRED')),
                  fired_by    TEXT,
                  fired_count INT NOT NULL DEFAULT 0
                )""");
    }

    @BeforeEach
    void emptyTable() {
        jdbc.update("DELETE FROM worker_timers");
    }

    @AfterEach
    void stopWorkers() {
        workers.forEach(PollingWorker::stop);
    }

    @Test
    void aDueRowIsHandledExactlyOnceAcrossTwoInstances() {
        Instant now = Instant.now();
        for (int i = 0; i < 300; i++) {
            schedule(now.minusMillis(i));
        }
        schedule(now.plus(Duration.ofHours(1)));

        start("a", firing("a"), Clock.systemUTC());
        start("b", firing("b"), Clock.systemUTC());

        Topics.await(Duration.ofSeconds(20), () -> count("status = 'FIRED'") == 300);
        assertThat(count("fired_count > 1")).isZero();
        assertThat(count("status = 'SCHEDULED'")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT DISTINCT fired_by FROM worker_timers WHERE fired_by IS NOT NULL",
                String.class)).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void aHandlerCrashRollsBackAndAnotherInstancePicksTheRowUp() {
        long timer = schedule(Instant.now().minusSeconds(1));
        CountDownLatch crashed = new CountDownLatch(1);
        start("crashing", item -> {
            jdbc.update("UPDATE worker_timers SET fired_count = fired_count + 1 WHERE id = ?", item.id());
            crashed.countDown();
            throw new IllegalStateException("instance crashed while handling");
        }, Clock.systemUTC());
        Topics.await(Duration.ofSeconds(10), () -> crashed.getCount() == 0);

        start("healthy", firing("healthy"), Clock.systemUTC());

        Topics.await(Duration.ofSeconds(10), () -> count("status = 'FIRED'") == 1);
        Map<String, Object> row = jdbc.queryForMap("SELECT fired_by, fired_count FROM worker_timers WHERE id = ?",
                timer);
        assertThat(row).containsEntry("fired_by", "healthy").containsEntry("fired_count", 1);
    }

    @Test
    void aRowWhoseHandlerFailsRollsBackAloneAndTheRestOfTheBatchFires() {
        Instant now = Instant.now();
        long poison = schedule(now.minusSeconds(60));
        for (int i = 0; i < 25; i++) {
            schedule(now.minusSeconds(30).plusMillis(i));
        }
        DueItemHandler<Timer> firing = firing("a");
        start("a", item -> {
            if (item.id() == poison) {
                jdbc.update("UPDATE worker_timers SET fired_count = fired_count + 1 WHERE id = ?", item.id());
                throw new IllegalStateException("this row cannot be handled");
            }
            firing.handle(item);
        }, Clock.systemUTC());

        Topics.await(Duration.ofSeconds(10), () -> count("status = 'FIRED'") == 25);
        Map<String, Object> row = jdbc.queryForMap("SELECT status, fired_count FROM worker_timers WHERE id = ?",
                poison);
        assertThat(row).containsEntry("status", "SCHEDULED").containsEntry("fired_count", 0);
    }

    @Test
    void lagIsTheAgeOfTheOldestDueRow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-29T12:00:00Z"));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        schedule(Instant.parse("2026-10-29T11:59:30Z"));
        schedule(Instant.parse("2026-10-29T11:59:50Z"));
        schedule(Instant.parse("2026-10-29T12:05:00Z"));
        PollingWorker<Timer> stuck = start("stuck", item -> {
            throw new IllegalStateException("cannot handle yet");
        }, clock, meters);

        Topics.await(Duration.ofSeconds(5), () -> lag(meters, "stuck") == 30.0);
        clock.advance(Duration.ofSeconds(5));
        Topics.await(Duration.ofSeconds(5), () -> lag(meters, "stuck") == 35.0);

        stuck.stop();
        jdbc.update("UPDATE worker_timers SET status = 'FIRED' WHERE due_at < '2026-10-29T12:01:00Z'");
        start("idle", firing("idle"), clock, meters);
        Topics.await(Duration.ofSeconds(5), () -> lag(meters, "idle") == 0.0);
    }

    private PollingWorker<Timer> start(String name, DueItemHandler<Timer> handler, Clock clock) {
        return start(name, handler, clock, new SimpleMeterRegistry());
    }

    private PollingWorker<Timer> start(String name, DueItemHandler<Timer> handler, Clock clock,
            SimpleMeterRegistry meters) {
        PollingWorker<Timer> worker = new PollingWorker<>(
                new PollingWorker.Settings(name, 10, Duration.ofMillis(50), 2),
                jdbc, new DataSourceTransactionManager(dataSource), DUE,
                (rs, row) -> new Timer(rs.getLong("id"), rs.getObject("due_at", OffsetDateTime.class).toInstant()),
                handler, OLDEST_DUE, clock, meters);
        worker.start();
        workers.add(worker);
        return worker;
    }

    private static DueItemHandler<Timer> firing(String instance) {
        return item -> jdbc.update(
                "UPDATE worker_timers SET status = 'FIRED', fired_by = ?, fired_count = fired_count + 1 WHERE id = ?",
                instance, item.id());
    }

    private static long schedule(Instant dueAt) {
        return jdbc.queryForObject("INSERT INTO worker_timers (due_at) VALUES (?) RETURNING id", Long.class,
                OffsetDateTime.ofInstant(dueAt, ZoneOffset.UTC));
    }

    private static int count(String condition) {
        return jdbc.queryForObject("SELECT count(*) FROM worker_timers WHERE " + condition, Integer.class);
    }

    private static double lag(SimpleMeterRegistry meters, String worker) {
        return meters.get("tpe.worker.lag").tag("worker", worker).gauge().value();
    }
}
