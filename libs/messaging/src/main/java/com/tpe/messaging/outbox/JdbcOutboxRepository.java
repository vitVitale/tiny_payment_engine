package com.tpe.messaging.outbox;

import java.time.Duration;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link OutboxRepository} with plain SQL; no {@code SKIP LOCKED}: only the leader reads (ADR-006). */
public class JdbcOutboxRepository implements OutboxRepository {

    private final JdbcTemplate jdbc;

    public JdbcOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<OutboxRow> oldestUnpublished(Duration visibilityWindow, int limit) {
        return jdbc.query("""
                SELECT id, topic, message_key, payload::text AS payload, headers::text AS headers
                FROM outbox
                WHERE published_at IS NULL AND created_at < now() - ? * interval '1 millisecond'
                ORDER BY id
                LIMIT ?""",
                (rs, row) -> new OutboxRow(rs.getLong("id"), rs.getString("topic"), rs.getString("message_key"),
                        rs.getString("payload"), OutboxHeaders.fromJson(rs.getString("headers"))),
                visibilityWindow.toMillis(), limit);
    }

    @Override
    public List<Duration> markPublished(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                UPDATE outbox SET published_at = now()
                WHERE id = ANY(?) AND published_at IS NULL
                RETURNING (extract(epoch FROM now() - created_at) * 1000000)::bigint AS latency_micros""",
                (rs, row) -> Duration.ofNanos(rs.getLong("latency_micros") * 1000),
                (Object) ids.toArray(Long[]::new));
    }

    @Override
    public long countUnpublished() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NULL", Long.class);
        return count == null ? 0 : count;
    }
}
