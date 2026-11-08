package com.tpe.ledgerservice.infrastructure.persistence;

import com.tpe.ledgerservice.application.reservation.PendingCreditRepository;
import com.tpe.ledgerservice.domain.reservation.IncomingReference;
import com.tpe.ledgerservice.domain.reservation.PendingCredit;
import com.tpe.ledgerservice.domain.reservation.PendingCreditStatus;
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
class JdbcPendingCreditRepository implements PendingCreditRepository {

    private static final String SELECT = """
            SELECT pending_credit_id, account_id, amount_minor, status, payment_id, tx_id, debtor_agent_bic,
                   created_at, finalised_at, entry_id, tombstone
              FROM pending_credits WHERE pending_credit_id = ?""";

    private final JdbcClient jdbc;

    JdbcPendingCreditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PendingCredit> find(UUID pendingCreditId) {
        return jdbc.sql(SELECT).param(pendingCreditId).query(JdbcPendingCreditRepository::pendingCredit).optional();
    }

    @Override
    public Optional<PendingCredit> findForUpdate(UUID pendingCreditId) {
        return jdbc.sql(SELECT + " FOR UPDATE").param(pendingCreditId)
                .query(JdbcPendingCreditRepository::pendingCredit).optional();
    }

    @Override
    public boolean insertIfAbsent(PendingCredit credit) {
        IncomingReference reference = credit.reference();
        return jdbc.sql("""
                INSERT INTO pending_credits (pending_credit_id, account_id, amount_minor, status, payment_id, tx_id,
                                             debtor_agent_bic, created_at, finalised_at, tombstone)
                VALUES (:id, :accountId, :amount, :status, :paymentId, :txId, :debtorAgentBic, :createdAt,
                        :finalisedAt, :tombstone)
                ON CONFLICT (pending_credit_id) DO NOTHING""")
                .param("id", credit.pendingCreditId())
                .param("accountId", credit.accountId())
                .param("amount", credit.amountMinor())
                .param("status", credit.status().name())
                .param("paymentId", reference == null ? null : reference.paymentId())
                .param("txId", reference == null ? null : reference.txId())
                .param("debtorAgentBic", reference == null ? null : reference.debtorAgentBic())
                .param("createdAt", utc(credit.createdAt()))
                .param("finalisedAt", utc(credit.finalisedAt()))
                .param("tombstone", credit.tombstone())
                .update() == 1;
    }

    @Override
    public boolean markCancelled(PendingCredit cancelled) {
        return jdbc.sql("""
                UPDATE pending_credits SET status = 'CANCELLED', finalised_at = ?
                 WHERE pending_credit_id = ? AND status = 'ACTIVE'""")
                .params(utc(cancelled.finalisedAt()), cancelled.pendingCreditId())
                .update() == 1;
    }

    @Override
    public boolean markConfirmed(PendingCredit confirmed) {
        return jdbc.sql("""
                UPDATE pending_credits SET status = 'CONFIRMED', finalised_at = ?, entry_id = ?
                 WHERE pending_credit_id = ? AND status = 'ACTIVE'""")
                .params(utc(confirmed.finalisedAt()), confirmed.entryId(), confirmed.pendingCreditId())
                .update() == 1;
    }

    @Override
    public int deleteTombstonesCreatedBefore(Instant cutoff, int limit) {
        return jdbc.sql("""
                DELETE FROM pending_credits WHERE pending_credit_id IN (
                  SELECT pending_credit_id FROM pending_credits WHERE tombstone AND created_at < ? LIMIT ?)""")
                .params(utc(cutoff), limit)
                .update();
    }

    private static PendingCredit pendingCredit(ResultSet rs, int row) throws SQLException {
        boolean tombstone = rs.getBoolean("tombstone");
        return new PendingCredit(
                rs.getObject("pending_credit_id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getObject("amount_minor", Long.class),
                PendingCreditStatus.valueOf(rs.getString("status")),
                tombstone ? null : new IncomingReference(rs.getObject("payment_id", UUID.class),
                        rs.getString("tx_id"), rs.getString("debtor_agent_bic")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                instant(rs.getObject("finalised_at", OffsetDateTime.class)),
                rs.getObject("entry_id", UUID.class),
                tombstone);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
