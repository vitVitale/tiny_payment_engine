package com.tpe.vopservice.infrastructure.jobs;

import com.tpe.messaging.worker.PollingWorker;
import com.tpe.observability.context.Spans;
import com.tpe.vopservice.application.references.VopResults;
import com.tpe.vopservice.application.retention.VopRetention;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The hourly retention job (specs/13-vop-service.md §6.3): a {@link PollingWorker} over the {@code vop_results} rows
 * due for {@link VopRetention}: a name past {@code result-retention} after expiry, or a row 30 days old. Plus the
 * gauge {@code tpe.vop.retention.overdue} of SLO-VOP-06 (25 §5.4): rows still named 2 h after their retention passed.
 */
@Configuration(proxyBeanMethods = false)
class RetentionJob {

    /** SLO-VOP-06: names cleared within 2 h of the retention passing; the hourly job fits twice. */
    static final Duration OVERDUE_AFTER = Duration.ofHours(2);

    private static final String NAMED = "(r.payee_name IS NOT NULL OR r.actual_name IS NOT NULL)";

    /**
     * Rows still named past {@code retention} after expiry (partial expiry index) or 30 days old (creation index);
     * parameters (now, batch size).
     */
    static String dueSql(Duration retention) {
        return """
                WITH now AS (SELECT ?::timestamptz AS t)
                SELECT r.vop_reference FROM vop_results r, now
                 WHERE (%1$s AND r.expires_at < now.t - interval '%2$d seconds')
                    OR r.created_at < now.t - interval '%3$d seconds'
                 ORDER BY r.expires_at LIMIT ? FOR UPDATE OF r SKIP LOCKED"""
                .formatted(NAMED, retention.toSeconds(), VopRetention.ROW_LIFETIME.toSeconds());
    }

    /**
     * When the oldest due row became due, {@code NULL} if none; parameter (now). {@code NOT MATERIALIZED}: the CTE is
     * read twice, and inlining it keeps each {@code min} an index-ordered lookup.
     */
    static String oldestDueSql(Duration retention) {
        return """
                WITH now AS NOT MATERIALIZED (SELECT ?::timestamptz AS t)
                SELECT least(
                  (SELECT min(r.expires_at) FROM vop_results r, now
                    WHERE %1$s AND r.expires_at < now.t - interval '%2$d seconds') + interval '%2$d seconds',
                  (SELECT min(r.created_at) FROM vop_results r, now
                    WHERE r.created_at < now.t - interval '%3$d seconds') + interval '%3$d seconds')"""
                .formatted(NAMED, retention.toSeconds(), VopRetention.ROW_LIFETIME.toSeconds());
    }

    @Bean
    PollingWorker<String> vopRetentionWorker(VopRetention retention, JdbcTemplate jdbc,
            PlatformTransactionManager transactions, Clock clock, ObjectProvider<MeterRegistry> registry,
            ObjectProvider<Spans> spans,
            @Value("${tpe.vop.result-retention}") Duration resultRetention,
            @Value("${tpe.vop.retention.poll-interval}") Duration pollInterval) {
        return new PollingWorker<>(new PollingWorker.Settings("vop-retention", 500, pollInterval, 1), jdbc,
                transactions, dueSql(resultRetention), (rs, row) -> rs.getString("vop_reference"), retention,
                oldestDueSql(resultRetention), clock, registry.getIfAvailable(SimpleMeterRegistry::new),
                spans.getIfAvailable(Spans::noop));
    }

    @Bean
    Gauge vopRetentionOverdue(VopResults results, Clock clock, MeterRegistry registry,
            @Value("${tpe.vop.result-retention}") Duration resultRetention) {
        return Gauge.builder("tpe.vop.retention.overdue",
                        () -> results.countNamedExpiredBefore(
                                clock.instant().minus(resultRetention).minus(OVERDUE_AFTER)))
                .description("Verification results still holding names 2 h after their retention passed")
                .register(registry);
    }
}
