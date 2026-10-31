package com.tpe.messaging.worker;

import com.tpe.observability.context.Spans;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer.SpanInScope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * "Do X at time T, exactly once, on any instance" (specs/00-engineering-baseline.md §10, 01 §3.5). Each of
 * {@code threads} threads loops: in one transaction, select up to {@code batchSize} due rows with
 * {@code FOR UPDATE SKIP LOCKED} and hand each to the {@link DueItemHandler}; commit. Other instances skip the locked
 * rows. Each item runs behind a savepoint: a handler that throws rolls back that item's changes only, the rest of the
 * batch commits and the failed row stays due, so one row that keeps failing does not stop the worker. A crashed
 * instance rolls the whole transaction back and another instance picks the rows up. A thread sleeps
 * {@code pollInterval} after a batch that did not fire in full. Each handled row is one {@code worker <name>} span,
 * in scope while the handler runs and with an error status if it throws; a poll that finds no due rows creates no
 * spans (specs/26-observability.md §4.2).
 *
 * <p>The queries take "now" from the {@link Clock}, so tests control time:
 * <ul>
 *   <li>{@code dueSql}: parameters (now, batch size), e.g. {@code … WHERE status = 'SCHEDULED' AND due_at <= ? ORDER BY
 *       due_at LIMIT ? FOR UPDATE SKIP LOCKED};</li>
 *   <li>{@code oldestDueSql}: parameter (now), returns the due time of the oldest due row or {@code NULL}, e.g.
 *       {@code SELECT min(due_at) … WHERE status = 'SCHEDULED' AND due_at <= ?}. Run after each poll for the gauge
 *       {@code tpe.worker.lag{worker}}: the oldest due row's age in seconds, 0 when nothing is due.</li>
 * </ul>
 */
public final class PollingWorker<T> implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PollingWorker.class);

    /**
     * @param name the {@code worker} tag and thread name
     * @param pollInterval 100 ms for payment-path timers, 1 s for everything else (00 §10)
     */
    public record Settings(String name, int batchSize, Duration pollInterval, int threads) {

        public Settings {
            Objects.requireNonNull(name, "name");
            if (batchSize < 1 || threads < 1) {
                throw new IllegalArgumentException("batchSize and threads must be at least 1");
            }
        }
    }

    private final Settings settings;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final String dueSql;
    private final RowMapper<T> mapper;
    private final DueItemHandler<T> handler;
    private final String oldestDueSql;
    private final Clock clock;
    private final Spans spans;
    private final AtomicLong lagMillis = new AtomicLong();
    private final List<Thread> threads = new ArrayList<>();

    private volatile boolean running;

    public PollingWorker(Settings settings, JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            String dueSql, RowMapper<T> mapper, DueItemHandler<T> handler, String oldestDueSql, Clock clock,
            MeterRegistry registry) {
        this(settings, jdbc, transactionManager, dueSql, mapper, handler, oldestDueSql, clock, registry,
                Spans.noop());
    }

    /** @param spans gives the span of each handled row */
    public PollingWorker(Settings settings, JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            String dueSql, RowMapper<T> mapper, DueItemHandler<T> handler, String oldestDueSql, Clock clock,
            MeterRegistry registry, Spans spans) {
        this.settings = settings;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.dueSql = dueSql;
        this.mapper = mapper;
        this.handler = handler;
        this.oldestDueSql = oldestDueSql;
        this.clock = clock;
        this.spans = spans;
        Gauge.builder("tpe.worker.lag", lagMillis, millis -> millis.get() / 1000.0)
                .description("Age of the oldest due row of the worker")
                .baseUnit("seconds")
                .tag("worker", settings.name())
                .register(registry);
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        for (int i = 0; i < settings.threads(); i++) {
            threads.add(Thread.ofPlatform().name("worker-" + settings.name() + "-" + i).daemon().start(this::run));
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        threads.forEach(Thread::interrupt);
        for (Thread thread : threads) {
            try {
                thread.join(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        threads.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void run() {
        while (running) {
            boolean full = false;
            try {
                full = pollOnce() == settings.batchSize();
            } catch (RuntimeException e) {
                if (running) {
                    log.warn("Worker {} failed a batch, its rows are due again: {}", settings.name(), e.toString());
                }
            }
            measureLag();
            if (!full && running) {
                sleep(settings.pollInterval());
            }
        }
    }

    /** One transaction: lock up to a batch of due rows and handle each; returns how many were handled. */
    private int pollOnce() {
        Integer handled = transactions.execute(status -> {
            int succeeded = 0;
            for (T item : jdbc.query(dueSql, mapper, now(), settings.batchSize())) {
                Span span = spans.newTrace("worker " + settings.name()).start();
                Object savepoint = status.createSavepoint();
                try (SpanInScope scope = spans.inScope(span)) {
                    handler.handle(item);
                    status.releaseSavepoint(savepoint);
                    succeeded++;
                } catch (RuntimeException e) {
                    span.error(e);
                    status.rollbackToSavepoint(savepoint);
                    log.warn("Worker {} could not handle {}, it stays due: {}", settings.name(), item, e.toString());
                } finally {
                    span.end();
                }
            }
            return succeeded;
        });
        return handled == null ? 0 : handled;
    }

    private void measureLag() {
        try {
            Instant now = clock.instant();
            OffsetDateTime oldest = jdbc.queryForObject(oldestDueSql, OffsetDateTime.class, at(now));
            lagMillis.set(oldest == null ? 0 : Math.max(0, Duration.between(oldest.toInstant(), now).toMillis()));
        } catch (RuntimeException e) {
            log.debug("Worker {} could not measure its lag", settings.name(), e);
        }
    }

    private OffsetDateTime now() {
        return at(clock.instant());
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // stop() interrupts; the loop checks running
        }
    }
}
