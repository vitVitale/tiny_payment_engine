package com.tpe.ledgerservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.contracts.TechnicalAccounts;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The deferred balanced-entry trigger (specs/12-ledger-service.md §5), the safety net behind the engine: plain JDBC,
 * bypassing the engine, cannot commit an entry that does not balance or has fewer than 2 postings.
 */
class BalancedEntryGuardIT extends LedgerStack {

    private static final UUID CSM = TechnicalAccounts.CSM_SETTLEMENT_ID;
    private static final UUID FUNDING = TechnicalAccounts.FUNDING_ID;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @Test
    void LED_AC_12_anEntryWhosePostingsDoNotBalanceCannotBeCommittedNorOneWithASinglePosting() throws SQLException {
        UUID unbalanced = UUID.randomUUID();
        UUID single = UUID.randomUUID();

        assertRefusedAtCommit(unbalanced, new Line(FUNDING, "DR", 100), new Line(CSM, "CR", 99));
        assertRefusedAtCommit(single, new Line(CSM, "CR", 100));

        assertThat(entries(unbalanced)).isZero();
        assertThat(entries(single)).isZero();
    }

    @Test
    void anEntryWithoutPostingsCannotBeCommitted() throws SQLException {
        UUID empty = UUID.randomUUID();

        assertRefusedAtCommit(empty);

        assertThat(entries(empty)).isZero();
    }

    @Test
    void aBalancedEntryInsertedPostingByPostingCommits() throws SQLException {
        UUID balanced = UUID.randomUUID();

        try (Connection connection = transaction()) {
            insert(connection, balanced, new Line(FUNDING, "DR", 60), new Line(CSM, "CR", 40),
                    new Line(CSM, "CR", 20));
            connection.commit();
        }

        assertThat(entries(balanced)).isOne();
    }

    @Test
    void changingAPostingOfACommittedEntrySoItNoLongerBalancesCannotBeCommitted() throws SQLException {
        UUID entry = UUID.randomUUID();
        try (Connection connection = transaction()) {
            insert(connection, entry, new Line(FUNDING, "DR", 50), new Line(CSM, "CR", 50));
            connection.commit();
        }

        try (Connection connection = transaction()) {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE postings SET amount_minor = 51 WHERE entry_id = ? AND side = 'CR'")) {
                update.setObject(1, entry);
                update.executeUpdate();
            }
            assertThatThrownBy(connection::commit).isInstanceOf(SQLException.class)
                    .hasMessageContaining("does not balance");
        }
    }

    private void assertRefusedAtCommit(UUID entryId, Line... lines) throws SQLException {
        try (Connection connection = transaction()) {
            // deferred: every statement succeeds, the commit does not
            insert(connection, entryId, lines);
            assertThatThrownBy(connection::commit)
                    .isInstanceOfSatisfying(SQLException.class,
                            e -> assertThat(e.getSQLState()).isEqualTo("23514"));
        }
    }

    private Connection transaction() throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private static void insert(Connection connection, UUID entryId, Line... lines) throws SQLException {
        OffsetDateTime now = CLOCK.instant().atOffset(ZoneOffset.UTC);
        try (PreparedStatement entry = connection.prepareStatement(
                "INSERT INTO journal_entries (entry_id, entry_type, created_at) VALUES (?, 'FUNDING', ?)")) {
            entry.setObject(1, entryId);
            entry.setObject(2, now);
            entry.executeUpdate();
        }
        for (Line line : lines) {
            try (PreparedStatement posting = connection.prepareStatement("""
                    INSERT INTO postings (entry_id, account_id, side, amount_minor, created_at)
                    VALUES (?, ?, ?, ?, ?)""")) {
                posting.setObject(1, entryId);
                posting.setObject(2, line.accountId());
                posting.setString(3, line.side());
                posting.setLong(4, line.amountMinor());
                posting.setObject(5, now);
                posting.executeUpdate();
            }
        }
    }

    private long entries(UUID entryId) {
        return jdbc.sql("SELECT count(*) FROM journal_entries WHERE entry_id = ?").param(entryId)
                .query(Long.class).single();
    }

    private record Line(UUID accountId, String side, long amountMinor) {}
}
