package com.tpe.messaging.inbox;

import com.tpe.observability.context.Spans;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Exactly-once handling on top of at-least-once delivery (specs/01-shared-libraries.md §3.6): records a message in
 * the {@code inbox} in the business transaction, so the business change and the record commit or roll back together.
 * Sets {@code tpe.inbox.duplicate} on the current span, the listener's {@code process} span (26 §5.2).
 */
public final class InboxGuard {

    private final JdbcTemplate jdbc;
    private final Counter duplicates;
    private final Spans spans;

    public InboxGuard(JdbcTemplate jdbc, MeterRegistry registry) {
        this(jdbc, registry, Spans.noop());
    }

    public InboxGuard(JdbcTemplate jdbc, MeterRegistry registry, Spans spans) {
        this.jdbc = jdbc;
        this.spans = spans;
        this.duplicates = Counter.builder("tpe.inbox.duplicates")
                .description("Consumed messages that were already handled")
                .register(registry);
    }

    /**
     * Whether this message is handled for the first time; if {@code false}, the handler returns without side effects.
     * MUST be called in the transaction of the business change; throws {@link IllegalStateException} otherwise.
     *
     * @param dedupKey the topic's deduplication key, e.g. the {@code eventId} or 02 §4's
     *     {@code <debtorAgentBic>:<txId>:<type>:<outcome>}
     */
    public boolean firstTime(String dedupKey, String topic, UUID eventId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("InboxGuard.firstTime needs the business transaction "
                    + "(specs/01-shared-libraries.md §3.6)");
        }
        int inserted = jdbc.update("""
                INSERT INTO inbox (dedup_key, topic, event_id) VALUES (?, ?, ?)
                ON CONFLICT (dedup_key) DO NOTHING""", dedupKey, topic, eventId);
        boolean duplicate = inserted == 0;
        spans.current().ifPresent(span -> span.tag("tpe.inbox.duplicate", duplicate));
        if (duplicate) {
            duplicates.increment();
        }
        return !duplicate;
    }
}
