package com.tpe.ledgerservice.infrastructure.persistence;

import com.tpe.ledgerservice.application.reservation.HoldRepository;
import com.tpe.ledgerservice.domain.reservation.Hold;
import com.tpe.ledgerservice.domain.reservation.HoldStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcHoldRepository implements HoldRepository {

    private final JdbcClient jdbc;

    JdbcHoldRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Hold> find(UUID holdId) {
        return jdbc.sql("""
                SELECT hold_id, account_id, amount_minor, status, payment_id, tx_id, created_at, finalised_at, tombstone
                  FROM holds WHERE hold_id = ?""")
                .param(holdId)
                .query(JdbcHoldRepository::hold)
                .optional();
    }

    @Override
    public boolean insertIfAbsent(Hold hold) {
        return jdbc.sql("""
                INSERT INTO holds (hold_id, account_id, amount_minor, status, payment_id, tx_id, created_at,
                                   finalised_at, tombstone)
                VALUES (:holdId, :accountId, :amount, :status, :paymentId, :txId, :createdAt, :finalisedAt, :tombstone)
                ON CONFLICT (hold_id) DO NOTHING""")
                .param("holdId", hold.holdId())
                .param("accountId", hold.accountId())
                .param("amount", hold.amountMinor())
                .param("status", hold.status().name())
                .param("paymentId", hold.paymentId())
                .param("txId", hold.txId())
                .param("createdAt", utc(hold.createdAt()))
                .param("finalisedAt", utc(hold.finalisedAt()))
                .param("tombstone", hold.tombstone())
                .update() == 1;
    }

    @Override
    public boolean markReleased(Hold released) {
        return jdbc.sql("""
                UPDATE holds SET status = 'RELEASED', finalised_at = ?
                 WHERE hold_id = ? AND status = 'ACTIVE'""")
                .params(utc(released.finalisedAt()), released.holdId())
                .update() == 1;
    }

    @Override
    public int deleteTombstonesCreatedBefore(Instant cutoff, int limit) {
        return jdbc.sql("""
                DELETE FROM holds WHERE hold_id IN (
                  SELECT hold_id FROM holds WHERE tombstone AND created_at < ? LIMIT ?)""")
                .params(utc(cutoff), limit)
                .update();
    }

    private static Hold hold(ResultSet rs, int row) throws SQLException {
        return new Hold(
                rs.getObject("hold_id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getObject("amount_minor", Long.class),
                HoldStatus.valueOf(rs.getString("status")),
                rs.getObject("payment_id", UUID.class),
                rs.getString("tx_id"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                instant(rs.getObject("finalised_at", OffsetDateTime.class)),
                rs.getBoolean("tombstone"));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
