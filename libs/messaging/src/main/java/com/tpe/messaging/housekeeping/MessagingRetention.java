package com.tpe.messaging.housekeeping;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Outbox and inbox retention (specs/01-shared-libraries.md §3.1): published outbox rows go after {@code outbox}, inbox
 * rows after {@code inbox}; an unpublished outbox row is never deleted. Deletes in chunks of {@code chunk} rows, each
 * its own statement, so no purge holds locks for long. Any instance may run it at any time: the deletes are
 * idempotent.
 */
public final class MessagingRetention {

    /** @param inbox MUST be longer than the retention of every consumed {@code delete} topic (7 d, 02 §2) */
    public record Settings(Duration outbox, Duration inbox, int chunk) {

        /** 7 d outbox, 8 d inbox, 10 000 rows per delete. */
        public static Settings defaults() {
            return new Settings(Duration.ofDays(7), Duration.ofDays(8), 10_000);
        }
    }

    /** Rows deleted by one {@link #purge}. */
    public record Purged(int outbox, int inbox) {}

    private final JdbcTemplate jdbc;
    private final Settings settings;
    private final Clock clock;

    public MessagingRetention(JdbcTemplate jdbc, Settings settings, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.clock = clock;
    }

    /** Deletes every row past its retention. */
    public Purged purge() {
        int outbox = deleteAll("""
                DELETE FROM outbox WHERE id IN (
                  SELECT id FROM outbox WHERE published_at < ? LIMIT ?)""", settings.outbox());
        int inbox = deleteAll("""
                DELETE FROM inbox WHERE dedup_key IN (
                  SELECT dedup_key FROM inbox WHERE processed_at < ? LIMIT ?)""", settings.inbox());
        return new Purged(outbox, inbox);
    }

    private int deleteAll(String sql, Duration retention) {
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(clock.instant().minus(retention), ZoneOffset.UTC);
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.update(sql, cutoff, settings.chunk());
            total += deleted;
        } while (deleted == settings.chunk());
        return total;
    }
}
