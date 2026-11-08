package com.tpe.ledgerservice.infrastructure.jobs;

import com.tpe.ledgerservice.application.account.ClosedAccountTombstones;
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
 * The daily job deleting closed accounts from {@code accounts.events} (specs/12-ledger-service.md §4, ADR-007): a
 * {@link PollingWorker} over the closed accounts 13 months past {@code closed_at} whose tombstone has not gone out,
 * started and stopped with the application context.
 */
@Configuration(proxyBeanMethods = false)
class ClosedAccountTombstoneJob {

    /**
     * When the account leaves {@code accounts.events}: 13 months after its close (ADR-007), counted on the UTC
     * calendar whatever the session's time zone, a month end clamped as {@code ZonedDateTime.plusMonths} does
     * (31 Jan + 13 months = 28 Feb).
     */
    private static final String DUE_AT = "(closed_at AT TIME ZONE 'UTC' + interval '13 months') AT TIME ZONE 'UTC'";

    static final String DUE = """
            SELECT account_id FROM accounts
             WHERE status = 'CLOSED' AND events_tombstoned_at IS NULL AND %s <= ?::timestamptz
             ORDER BY closed_at LIMIT ? FOR UPDATE SKIP LOCKED""".formatted(DUE_AT);

    static final String OLDEST_DUE = """
            SELECT min(%1$s) FROM accounts
             WHERE status = 'CLOSED' AND events_tombstoned_at IS NULL AND %1$s <= ?::timestamptz"""
            .formatted(DUE_AT);

    @Bean
    PollingWorker<UUID> closedAccountTombstoneWorker(ClosedAccountTombstones tombstones, JdbcTemplate jdbc,
            PlatformTransactionManager transactions, Clock clock, ObjectProvider<MeterRegistry> registry,
            ObjectProvider<Spans> spans,
            @Value("${tpe.ledger.account-tombstones.poll-interval}") Duration pollInterval) {
        return new PollingWorker<>(new PollingWorker.Settings("account-tombstones", 100, pollInterval, 1), jdbc,
                transactions, DUE, (rs, row) -> rs.getObject("account_id", UUID.class), tombstones, OLDEST_DUE,
                clock, registry.getIfAvailable(SimpleMeterRegistry::new), spans.getIfAvailable(Spans::noop));
    }
}
