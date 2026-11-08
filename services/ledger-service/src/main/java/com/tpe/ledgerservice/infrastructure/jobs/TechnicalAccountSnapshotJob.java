package com.tpe.ledgerservice.infrastructure.jobs;

import com.tpe.ledgerservice.application.posting.TechnicalAccountSnapshots;
import com.tpe.messaging.worker.PollingWorker;
import com.tpe.observability.context.Spans;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The technical-account snapshot job (specs/12-ledger-service.md §6.3, §7): a {@link PollingWorker} that hands each
 * technical account to {@link TechnicalAccountSnapshots} one {@code tpe.ledger.snapshot.interval} after its latest
 * snapshot was taken or confirmed. The account's row is locked {@code FOR NO KEY UPDATE SKIP LOCKED}, not
 * {@code FOR UPDATE}: another instance skips it, while postings, whose foreign key takes {@code FOR KEY SHARE}, never
 * wait for it (ADR-005: no hot row).
 */
@Configuration(proxyBeanMethods = false)
class TechnicalAccountSnapshotJob {

    /** When a technical account is due: one interval ({@code %1$d} s) after its latest snapshot, at once without. */
    private static final String DUE_AT = """
            coalesce((SELECT max(s.taken_at) FROM balance_snapshots s WHERE s.account_id = a.account_id)
                     + make_interval(secs => %1$d), a.opened_at)""";

    static final String DUE = """
            SELECT a.account_id FROM accounts a WHERE a.kind = 'TECHNICAL' AND %s <= ?::timestamptz
             ORDER BY a.account_id LIMIT ? FOR NO KEY UPDATE OF a SKIP LOCKED""".formatted(DUE_AT);

    static final String OLDEST_DUE = """
            SELECT min(%1$s) FROM accounts a WHERE a.kind = 'TECHNICAL' AND %1$s <= ?::timestamptz""".formatted(DUE_AT);

    @Bean
    PollingWorker<UUID> technicalAccountSnapshotWorker(TechnicalAccountSnapshots snapshots, JdbcTemplate jdbc,
            PlatformTransactionManager transactions, Clock clock, ObjectProvider<MeterRegistry> registry,
            ObjectProvider<Spans> spans, @Value("${tpe.ledger.snapshot.interval}") Duration interval) {
        long seconds = interval.toSeconds();
        return new PollingWorker<>(new PollingWorker.Settings("technical-snapshots", 10, interval, 1), jdbc,
                transactions, DUE.formatted(seconds), (rs, row) -> rs.getObject("account_id", UUID.class), snapshots,
                OLDEST_DUE.formatted(seconds), clock, registry.getIfAvailable(SimpleMeterRegistry::new),
                spans.getIfAvailable(Spans::noop));
    }
}
