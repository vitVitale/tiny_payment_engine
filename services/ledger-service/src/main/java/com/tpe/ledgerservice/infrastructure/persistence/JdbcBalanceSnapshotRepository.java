package com.tpe.ledgerservice.infrastructure.persistence;

import com.tpe.ledgerservice.application.posting.BalanceSnapshotRepository;
import com.tpe.ledgerservice.domain.posting.BalanceSnapshot;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcBalanceSnapshotRepository implements BalanceSnapshotRepository {

    /** Σ CR − Σ DR, 0 without postings. */
    private static final String SIGNED_SUM =
            "coalesce(sum(CASE side WHEN 'CR' THEN amount_minor ELSE -amount_minor END), 0)";

    private final JdbcClient jdbc;

    JdbcBalanceSnapshotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BalanceSnapshot> latest(UUID accountId) {
        return jdbc.sql("""
                SELECT account_id, up_to_posting_id, balance_minor, taken_at FROM balance_snapshots
                 WHERE account_id = ? ORDER BY up_to_posting_id DESC LIMIT 1""")
                .param(accountId)
                .query((rs, row) -> new BalanceSnapshot(rs.getObject("account_id", UUID.class),
                        rs.getLong("up_to_posting_id"), rs.getLong("balance_minor"),
                        rs.getObject("taken_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    @Override
    public long lastPostingBefore(UUID accountId, Instant cutoff) {
        return jdbc.sql("SELECT coalesce(max(posting_id), 0) FROM postings WHERE account_id = ? AND created_at < ?")
                .params(accountId, cutoff.atOffset(ZoneOffset.UTC))
                .query(Long.class).single();
    }

    @Override
    public long sumBetween(UUID accountId, long afterPostingId, long upToPostingId) {
        return jdbc.sql("SELECT " + SIGNED_SUM
                        + " FROM postings WHERE account_id = ? AND posting_id > ? AND posting_id <= ?")
                .params(accountId, afterPostingId, upToPostingId)
                .query(Long.class).single();
    }

    @Override
    public void save(BalanceSnapshot snapshot) {
        jdbc.sql("""
                INSERT INTO balance_snapshots (account_id, up_to_posting_id, balance_minor, taken_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (account_id, up_to_posting_id)
                DO UPDATE SET taken_at = greatest(balance_snapshots.taken_at, EXCLUDED.taken_at)""")
                .params(snapshot.accountId(), snapshot.upToPostingId(), snapshot.balanceMinor(),
                        snapshot.takenAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    /** One statement, so the snapshot and the postings after it are read from the same committed state. */
    @Override
    public long derivedBalance(UUID accountId) {
        return jdbc.sql("""
                WITH latest AS (
                  SELECT up_to_posting_id, balance_minor FROM balance_snapshots
                   WHERE account_id = :accountId ORDER BY up_to_posting_id DESC LIMIT 1)
                SELECT coalesce((SELECT balance_minor FROM latest), 0)
                     + (SELECT %s FROM postings
                         WHERE account_id = :accountId
                           AND posting_id > coalesce((SELECT up_to_posting_id FROM latest), 0))"""
                        .formatted(SIGNED_SUM))
                .param("accountId", accountId)
                .query(Long.class).single();
    }
}
