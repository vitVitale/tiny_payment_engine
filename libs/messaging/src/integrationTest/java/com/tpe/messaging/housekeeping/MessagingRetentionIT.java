package com.tpe.messaging.housekeeping;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.messaging.MessagingContainers;
import com.tpe.messaging.housekeeping.MessagingRetention.Purged;
import com.tpe.paymentdomain.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Outbox and inbox retention (specs/01-shared-libraries.md §3.1) with controlled timestamps. Its own database. */
class MessagingRetentionIT {

    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");

    private static JdbcTemplate jdbc;

    private final MutableClock clock = new MutableClock(NOW);

    @BeforeAll
    static void migrate() {
        DataSource dataSource = MessagingContainers.dataSource("retention_it");
        MessagingContainers.migrate(dataSource);
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void empty() {
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM inbox");
    }

    @Test
    void deletesOnlyPublishedOutboxRowsOlderThanTheRetention() {
        UUID oldPublished = outbox(days(30), days(8));
        UUID recentPublished = outbox(days(30), days(6));
        UUID oldUnpublished = outbox(days(30), null);

        Purged purged = retention(MessagingRetention.Settings.defaults()).purge();

        assertThat(purged.outbox()).isEqualTo(1);
        assertThat(outboxEvents()).containsExactlyInAnyOrder(recentPublished, oldUnpublished)
                .doesNotContain(oldPublished);
    }

    @Test
    void deletesOnlyInboxRowsOlderThanTheRetention() {
        inbox("old", days(9));
        inbox("recent", days(7));

        Purged purged = retention(MessagingRetention.Settings.defaults()).purge();

        assertThat(purged.inbox()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT dedup_key FROM inbox", String.class)).containsExactly("recent");
    }

    @Test
    void theCutoffMovesWithTheClock() {
        inbox("ages", days(7));
        MessagingRetention retention = retention(MessagingRetention.Settings.defaults());

        assertThat(retention.purge().inbox()).isZero();
        clock.advance(Duration.ofDays(1).plusSeconds(1));
        assertThat(retention.purge().inbox()).isEqualTo(1);
    }

    @Test
    void deletesInChunksUntilNothingIsLeft() {
        for (int i = 0; i < 7; i++) {
            outbox(days(30), days(10));
            inbox("old-" + i, days(10));
        }

        Purged purged = retention(new MessagingRetention.Settings(Duration.ofDays(7), Duration.ofDays(8), 3)).purge();

        assertThat(purged).isEqualTo(new Purged(7, 7));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox", Integer.class)).isZero();
    }

    private MessagingRetention retention(MessagingRetention.Settings settings) {
        return new MessagingRetention(jdbc, settings, clock);
    }

    private static UUID outbox(Instant createdAt, Instant publishedAt) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox (event_id, topic, message_key, message_type, schema_version, payload, created_at,
                                    published_at)
                VALUES (?, 'retention.it', 'key', 'AcceptCreditTransfer', 1, '{}', ?, ?)""",
                eventId, at(createdAt), publishedAt == null ? null : at(publishedAt));
        return eventId;
    }

    private static void inbox(String dedupKey, Instant processedAt) {
        jdbc.update("INSERT INTO inbox (dedup_key, topic, event_id, processed_at) VALUES (?, 'retention.it', ?, ?)",
                dedupKey, UUID.randomUUID(), at(processedAt));
    }

    private static List<UUID> outboxEvents() {
        return jdbc.queryForList("SELECT event_id FROM outbox", UUID.class);
    }

    private static Instant days(int ago) {
        return NOW.minus(Duration.ofDays(ago));
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
