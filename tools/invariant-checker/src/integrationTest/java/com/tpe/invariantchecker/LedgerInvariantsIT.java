package com.tpe.invariantchecker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * INV-1, INV-2 and INV-12 (specs/20-testing-strategy.md §3) against the ledger schema of ledger-service's own
 * migrations. Each check has a negative test: the database seeded with exactly that violation makes it fail (20 §3).
 * The schema's guards (balanced-entry trigger, balance {@code CHECK}s) would refuse those rows, so a negative test
 * switches them off for its own session first, as a broken migration or a manual fix might.
 */
class LedgerInvariantsIT {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");
    private static final AtomicLong ACCOUNT_NUMBERS = new AtomicLong(9_000_000_000L);

    static {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + Path.of("../../services/ledger-service/src/main/resources/db/migration")
                        .toAbsolutePath().normalize())
                .placeholders(Map.of("bankCode", "10010010"))
                // as the service runs them (messaging's Flyway defaults): V9 creates an index CONCURRENTLY, which
                // waits forever behind a transactional schema-history lock
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .load()
                .migrate();
    }

    private Connection connection;

    @BeforeEach
    void connect() throws SQLException {
        connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @AfterEach
    void disconnect() throws SQLException {
        connection.close();
    }

    @Test
    void aConsistentLedgerPassesEveryCheck() throws SQLException {
        UUID payer = customer(0, 0);
        UUID payee = customer(0, 0);
        entry("FUNDING", posting(technical("FUNDING"), "DR", 1_000), posting(payer, "CR", 1_000));
        entry("ON_US", posting(payer, "DR", 300), posting(payee, "CR", 300));
        balances(payer, 700, 200);
        balances(payee, 300, 0);
        hold(payer, 200, "ACTIVE");
        hold(payer, 300, "SETTLED");
        pendingCredit(payee, 900, "ACTIVE");

        assertThat(LedgerInvariants.check(connection, Scope.accounts(List.of(payer, payee)))).isEmpty();
    }

    @Test
    void INV_1_anUnbalancedJournalEntryFails() throws SQLException {
        UUID account = customer(0, 0);
        unguarded();
        UUID entry = entry("FUNDING", posting(technical("FUNDING"), "DR", 1_000), posting(account, "CR", 900));
        balances(account, 900, 0);

        assertThat(LedgerInvariants.check(connection, Scope.accounts(List.of(account))))
                .extracting(Violation::invariant, Violation::subject)
                .containsExactly(tuple("INV-1", entry.toString()));
    }

    @Test
    void INV_1_anEntryWithASinglePostingFails() throws SQLException {
        UUID account = customer(0, 0);
        unguarded();
        UUID entry = entry("FUNDING", posting(account, "CR", 500));
        balances(account, 500, 0);

        assertThat(LedgerInvariants.check(connection, Scope.all()))
                .extracting(Violation::invariant, Violation::subject)
                .contains(tuple("INV-1", entry.toString()));
    }

    @Test
    void INV_2_aNegativeAvailableOrBookedBalanceFails() throws SQLException {
        UUID negativeBooked = customer(0, 0);
        UUID negativeAvailable = customer(0, 0);
        unguarded();
        entry("FUNDING", posting(negativeBooked, "DR", 50), posting(technical("FUNDING"), "CR", 50));
        balances(negativeBooked, -50, 0);
        entry("FUNDING", posting(technical("FUNDING"), "DR", 100), posting(negativeAvailable, "CR", 100));
        balances(negativeAvailable, 100, 150);
        hold(negativeAvailable, 150, "ACTIVE");

        assertThat(LedgerInvariants.check(connection, Scope.accounts(List.of(negativeBooked, negativeAvailable))))
                .extracting(Violation::invariant, Violation::subject)
                .containsExactlyInAnyOrder(
                        tuple("INV-2", negativeBooked.toString()),
                        tuple("INV-2", negativeAvailable.toString()));
    }

    @Test
    void INV_12_aBookedBalanceThatIsNotTheSumOfItsPostingsFails() throws SQLException {
        UUID account = customer(0, 0);
        entry("FUNDING", posting(technical("FUNDING"), "DR", 1_000), posting(account, "CR", 1_000));
        balances(account, 1_200, 0);

        assertThat(LedgerInvariants.check(connection, Scope.accounts(List.of(account))))
                .extracting(Violation::invariant, Violation::subject)
                .containsExactly(tuple("INV-12", account.toString()));
    }

    @Test
    void INV_12_aHeldAmountThatIsNotTheSumOfActiveHoldsFails() throws SQLException {
        UUID account = customer(0, 0);
        entry("FUNDING", posting(technical("FUNDING"), "DR", 1_000), posting(account, "CR", 1_000));
        balances(account, 1_000, 0);
        hold(account, 400, "ACTIVE");

        assertThat(LedgerInvariants.check(connection, Scope.accounts(List.of(account))))
                .extracting(Violation::invariant, Violation::subject)
                .containsExactly(tuple("INV-12", account.toString()));
    }

    @Test
    void INV_12_aPendingCreditCountedInTheBalanceFails() throws SQLException {
        UUID account = customer(0, 0);
        pendingCredit(account, 500, "ACTIVE");
        balances(account, 500, 0);

        assertThat(LedgerInvariants.check(connection, Scope.accounts(List.of(account))))
                .extracting(Violation::invariant, Violation::subject)
                .containsExactly(tuple("INV-12", account.toString()));
    }

    @Test
    void theAccountScopeIgnoresViolationsOnOtherAccounts() throws SQLException {
        UUID broken = customer(0, 0);
        UUID mine = customer(0, 0);
        balances(broken, 10, 0);

        assertThat(LedgerInvariants.check(connection, Scope.accounts(List.of(mine)))).isEmpty();
        assertThat(LedgerInvariants.check(connection, Scope.all()))
                .extracting(Violation::subject)
                .contains(broken.toString());
    }

    // ---- seeding

    /** Off for this session: the balanced-entry trigger (replica role) and the accounts' balance {@code CHECK}. */
    private void unguarded() throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            statement.execute("""
                    DO $$ DECLARE c text; BEGIN
                      FOR c IN SELECT conname FROM pg_constraint
                                WHERE conrelid = 'accounts'::regclass AND contype = 'c'
                                  AND pg_get_constraintdef(oid) LIKE '%booked_minor >= 0%' LOOP
                        EXECUTE format('ALTER TABLE accounts DROP CONSTRAINT %I', c);
                      END LOOP; END $$""");
        }
    }

    private UUID customer(long booked, long held) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var statement = connection.prepareStatement("""
                INSERT INTO accounts (account_id, iban, kind, customer_id, status, booked_minor, held_minor, opened_at)
                VALUES (?, ?, 'CUSTOMER', ?, 'OPEN', ?, ?, now())""")) {
            statement.setObject(1, id);
            statement.setString(2, "DE00" + "10010010" + ACCOUNT_NUMBERS.incrementAndGet());
            statement.setString(3, "CUST-" + id);
            statement.setLong(4, booked);
            statement.setLong(5, held);
            statement.executeUpdate();
        }
        return id;
    }

    private UUID technical(String code) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT account_id FROM accounts WHERE technical_code = ?")) {
            statement.setString(1, code);
            try (var rs = statement.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private record Posting(UUID account, String side, long amount) {}

    private static Posting posting(UUID account, String side, long amount) {
        return new Posting(account, side, amount);
    }

    private UUID entry(String type, Posting... postings) throws SQLException {
        UUID id = UUID.randomUUID();
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (var statement = connection.prepareStatement(
                    "INSERT INTO journal_entries (entry_id, entry_type, created_at) VALUES (?, ?, now())")) {
                statement.setObject(1, id);
                statement.setString(2, type);
                statement.executeUpdate();
            }
            for (Posting posting : postings) {
                try (var statement = connection.prepareStatement("""
                        INSERT INTO postings (entry_id, account_id, side, amount_minor, created_at)
                        VALUES (?, ?, ?, ?, now())""")) {
                    statement.setObject(1, id);
                    statement.setObject(2, posting.account());
                    statement.setString(3, posting.side());
                    statement.setLong(4, posting.amount());
                    statement.executeUpdate();
                }
            }
            connection.commit();
        } finally {
            connection.setAutoCommit(autoCommit);
        }
        return id;
    }

    private void balances(UUID account, long booked, long held) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE accounts SET booked_minor = ?, held_minor = ? WHERE account_id = ?")) {
            statement.setLong(1, booked);
            statement.setLong(2, held);
            statement.setObject(3, account);
            statement.executeUpdate();
        }
    }

    private void hold(UUID account, long amount, String status) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO holds (hold_id, account_id, amount_minor, status, created_at) VALUES (?, ?, ?, ?, now())""")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, account);
            statement.setLong(3, amount);
            statement.setString(4, status);
            statement.executeUpdate();
        }
    }

    private void pendingCredit(UUID account, long amount, String status) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO pending_credits (pending_credit_id, account_id, amount_minor, status, created_at)
                VALUES (?, ?, ?, ?, now())""")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, account);
            statement.setLong(3, amount);
            statement.setString(4, status);
            statement.executeUpdate();
        }
    }
}
