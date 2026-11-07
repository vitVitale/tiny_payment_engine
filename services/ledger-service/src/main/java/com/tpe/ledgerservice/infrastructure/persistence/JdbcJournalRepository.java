package com.tpe.ledgerservice.infrastructure.persistence;

import com.tpe.ledgerservice.application.posting.AccountPosting;
import com.tpe.ledgerservice.application.posting.JournalRepository;
import com.tpe.ledgerservice.domain.posting.EntryType;
import com.tpe.ledgerservice.domain.posting.JournalEntry;
import com.tpe.ledgerservice.domain.posting.Posting;
import com.tpe.ledgerservice.domain.posting.Side;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcJournalRepository implements JournalRepository {

    private final JdbcClient jdbc;

    JdbcJournalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(JournalEntry entry) {
        OffsetDateTime createdAt = entry.createdAt().atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO journal_entries (entry_id, entry_type, payment_id, tx_id, created_at)
                VALUES (?, ?, ?, ?, ?)""")
                .params(entry.entryId(), entry.type().name(), entry.paymentId(), entry.txId(), createdAt)
                .update();
        for (Posting posting : entry.postings()) {
            jdbc.sql("""
                    INSERT INTO postings (entry_id, account_id, side, amount_minor, created_at)
                    VALUES (?, ?, ?, ?, ?)""")
                    .params(entry.entryId(), posting.accountId(), posting.side().name(), posting.amountMinor(),
                            createdAt)
                    .update();
        }
    }

    @Override
    public Optional<JournalEntry> find(UUID entryId) {
        List<Posting> postings = jdbc.sql("""
                SELECT account_id, side, amount_minor FROM postings WHERE entry_id = ? ORDER BY posting_id""")
                .param(entryId)
                .query((rs, row) -> new Posting(rs.getObject("account_id", UUID.class),
                        Side.valueOf(rs.getString("side")), rs.getLong("amount_minor")))
                .list();
        return jdbc.sql("SELECT entry_type, payment_id, tx_id, created_at FROM journal_entries WHERE entry_id = ?")
                .param(entryId)
                .query((rs, row) -> new JournalEntry(entryId, EntryType.valueOf(rs.getString("entry_type")),
                        rs.getObject("payment_id", UUID.class), rs.getString("tx_id"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(), postings))
                .optional();
    }

    /** Walks {@code postings_account_time} backwards from the cursor. */
    @Override
    public List<AccountPosting> postings(UUID accountId, Instant from, Instant to, Long beforePostingId, int limit) {
        return jdbc.sql("""
                SELECT p.posting_id, p.entry_id, e.entry_type, p.side, p.amount_minor, e.payment_id, e.tx_id,
                       p.created_at
                  FROM postings p JOIN journal_entries e ON e.entry_id = p.entry_id
                 WHERE p.account_id = :accountId
                   AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR p.created_at >= :from)
                   AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR p.created_at < :to)
                   AND (CAST(:before AS BIGINT) IS NULL OR p.posting_id < :before)
                 ORDER BY p.posting_id DESC
                 LIMIT :limit""")
                .param("accountId", accountId)
                .param("from", utc(from))
                .param("to", utc(to))
                .param("before", beforePostingId)
                .param("limit", limit)
                .query(JdbcJournalRepository::posting)
                .list();
    }

    private static AccountPosting posting(ResultSet rs, int row) throws SQLException {
        return new AccountPosting(
                rs.getLong("posting_id"),
                rs.getObject("entry_id", UUID.class),
                EntryType.valueOf(rs.getString("entry_type")),
                Side.valueOf(rs.getString("side")),
                rs.getLong("amount_minor"),
                rs.getObject("payment_id", UUID.class),
                rs.getString("tx_id"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
