package com.tpe.invariantchecker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * INV-3, INV-8, INV-9 (cases) and INV-11 (orchestrator part) of specs/20-testing-strategy.md §3 against the schemas of
 * payment-orchestrator's and ledger-service's own migrations, each in its own database as in the compose stack. Each
 * check has a negative test: the databases seeded with exactly that violation make it fail (20 §3).
 */
class PaymentInvariantsIT {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");
    private static final AtomicLong NUMBERS = new AtomicLong(8_000_000_000L);
    private static final Duration TOLERANCE = Duration.ofSeconds(35);

    static {
        POSTGRES.start();
        try (var admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE payments");
            statement.execute("CREATE DATABASE ledger");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        migrate("payments", "payment-orchestrator", Map.of());
        migrate("ledger", "ledger-service", Map.of("bankCode", "10010010"));
    }

    private Connection payments;
    private Connection ledger;

    @BeforeEach
    void connect() throws SQLException {
        payments = connection("payments");
        ledger = connection("ledger");
    }

    @AfterEach
    void disconnect() throws SQLException {
        payments.close();
        ledger.close();
    }

    @Test
    void consistentPaymentsPassEveryCheck() throws SQLException {
        UUID onUs = payment("OUTGOING", "SETTLED", true, 300);
        UUID payer = customer();
        hold(onUs, payer, "SETTLED", entry("ON_US", onUs, posting(payer, "DR", 300), posting(customer(), "CR", 300)));
        UUID toPeer = payment("OUTGOING", "SETTLED", false, 500);
        hold(toPeer, payer, "SETTLED", entry("PAYMENT_OUT", toPeer, posting(payer, "DR", 500),
                posting(csmSettlement(), "CR", 500)));
        UUID rejected = payment("OUTGOING", "REJECTED", false, 700);
        hold(rejected, payer, "RELEASED", null);
        UUID neverHeld = payment("OUTGOING", "REJECTED", false, 900);
        UUID tombstoned = payment("OUTGOING", "REJECTED", false, 800);
        tombstone(tombstoned);
        UUID incoming = payment("INCOMING", "SETTLED", false, 200);
        entry("PAYMENT_IN", incoming, posting(csmSettlement(), "DR", 200), posting(payer, "CR", 200));
        UUID investigating = payment("OUTGOING", "INVESTIGATING", false, 100);
        exceptionCase(investigating, "UNRESOLVED_INVESTIGATION");
        List<UUID> all = List.of(onUs, toPeer, rejected, neverHeld, tombstoned, incoming, investigating);

        assertThat(PaymentInvariants.holdsMatchOutcomes(payments, ledger, all)).isEmpty();
        assertThat(PaymentInvariants.csmSettlementPostings(payments, ledger, all)).isEmpty();
        assertThat(PaymentInvariants.noUnexpectedCases(payments, all, Set.of("UNRESOLVED_INVESTIGATION"))).isEmpty();
        assertThat(PaymentInvariants.nothingStuck(payments, all, TOLERANCE)).isEmpty();
    }

    @Test
    void INV_3_aFinalPaymentWithAnActiveHoldFails() throws SQLException {
        UUID payment = payment("OUTGOING", "REJECTED", false, 300);
        hold(payment, customer(), "ACTIVE", null);

        assertViolation(PaymentInvariants.holdsMatchOutcomes(payments, ledger, List.of(payment)), "INV-3", payment);
    }

    @Test
    void INV_3_aSettledPaymentWhoseHoldWasReleasedFails() throws SQLException {
        UUID payment = payment("OUTGOING", "SETTLED", false, 300);
        hold(payment, customer(), "RELEASED", null);

        assertViolation(PaymentInvariants.holdsMatchOutcomes(payments, ledger, List.of(payment)), "INV-3", payment);
    }

    @Test
    void INV_3_aSettledPaymentWithoutAHoldFails() throws SQLException {
        UUID payment = payment("OUTGOING", "SETTLED", true, 300);

        assertViolation(PaymentInvariants.holdsMatchOutcomes(payments, ledger, List.of(payment)), "INV-3", payment);
    }

    @Test
    void INV_3_anOnUsPaymentSettledThroughTheCsmFails() throws SQLException {
        UUID payment = payment("OUTGOING", "SETTLED", true, 300);
        UUID payer = customer();
        hold(payment, payer, "SETTLED", entry("PAYMENT_OUT", payment, posting(payer, "DR", 300),
                posting(csmSettlement(), "CR", 300)));

        assertViolation(PaymentInvariants.holdsMatchOutcomes(payments, ledger, List.of(payment)), "INV-3", payment);
    }

    @Test
    void INV_3_aRejectedPaymentWhoseHoldWasSettledFails() throws SQLException {
        UUID payment = payment("OUTGOING", "REJECTED", false, 300);
        UUID payer = customer();
        hold(payment, payer, "SETTLED", entry("PAYMENT_OUT", payment, posting(payer, "DR", 300),
                posting(csmSettlement(), "CR", 300)));

        assertViolation(PaymentInvariants.holdsMatchOutcomes(payments, ledger, List.of(payment)), "INV-3", payment);
    }

    @Test
    void INV_8_anOnUsPaymentWithACsmSettlementPostingFails() throws SQLException {
        UUID payment = payment("OUTGOING", "SETTLED", true, 300);
        entry("PAYMENT_OUT", payment, posting(customer(), "DR", 300), posting(csmSettlement(), "CR", 300));

        assertViolation(PaymentInvariants.csmSettlementPostings(payments, ledger, List.of(payment)), "INV-8",
                payment);
    }

    @Test
    void INV_8_aSettledPaymentToAnotherPspWithoutItsCsmSettlementPostingFails() throws SQLException {
        UUID payment = payment("OUTGOING", "SETTLED", false, 300);

        assertViolation(PaymentInvariants.csmSettlementPostings(payments, ledger, List.of(payment)), "INV-8",
                payment);
    }

    @Test
    void INV_8_aSettledPaymentPostedTwiceFails() throws SQLException {
        UUID payment = payment("OUTGOING", "SETTLED", false, 300);
        UUID payer = customer();
        entry("PAYMENT_OUT", payment, posting(payer, "DR", 300), posting(csmSettlement(), "CR", 300));
        entry("PAYMENT_OUT", payment, posting(payer, "DR", 300), posting(csmSettlement(), "CR", 300));

        assertViolation(PaymentInvariants.csmSettlementPostings(payments, ledger, List.of(payment)), "INV-8",
                payment);
    }

    @Test
    void INV_8_anIncomingPaymentCreditedFromTheWrongSideFails() throws SQLException {
        UUID payment = payment("INCOMING", "SETTLED", false, 200);
        entry("PAYMENT_IN", payment, posting(customer(), "DR", 200), posting(csmSettlement(), "CR", 200));

        assertViolation(PaymentInvariants.csmSettlementPostings(payments, ledger, List.of(payment)), "INV-8",
                payment);
    }

    @Test
    void INV_9_anUnexpectedExceptionCaseFails() throws SQLException {
        UUID payment = payment("OUTGOING", "SETTLED", true, 300);
        exceptionCase(payment, "LEDGER_ACTION_STUCK");

        assertViolation(PaymentInvariants.noUnexpectedCases(payments, List.of(payment), Set.of()), "INV-9", payment);
    }

    @Test
    void INV_11_aPaymentThatIsNeitherFinalNorACaseFails() throws SQLException {
        UUID payment = payment("OUTGOING", "FUNDS_RESERVED", false, 300);

        assertViolation(PaymentInvariants.nothingStuck(payments, List.of(payment), TOLERANCE), "INV-11", payment);
    }

    @Test
    void INV_11_aScheduledTimerOfAFinalPaymentFails() throws SQLException {
        UUID payment = payment("OUTGOING", "SETTLED", true, 300);
        try (var statement = payments.prepareStatement("""
                INSERT INTO payment_timers (payment_id, type, due_at, status, created_at)
                VALUES (?, 'OUTGOING_DEADLINE', now(), 'SCHEDULED', now())""")) {
            statement.setObject(1, payment);
            statement.executeUpdate();
        }

        assertViolation(PaymentInvariants.nothingStuck(payments, List.of(payment), TOLERANCE), "INV-11", payment);
    }

    @Test
    void INV_11_anActionOverdueByMoreThanLeaseAndBackoffFails() throws SQLException {
        UUID overdue = payment("OUTGOING", "FUNDS_RESERVED", true, 300);
        UUID due = payment("OUTGOING", "FUNDS_RESERVED", true, 300);
        exceptionCase(overdue, "LEDGER_ACTION_STUCK");
        exceptionCase(due, "LEDGER_ACTION_STUCK");
        pendingAction(overdue, TOLERANCE.plusSeconds(5));
        pendingAction(due, TOLERANCE.minusSeconds(5));

        assertThat(PaymentInvariants.nothingStuck(payments, List.of(overdue, due), TOLERANCE))
                .extracting(Violation::invariant, Violation::subject)
                .containsExactly(tuple("INV-11", overdue.toString()));
    }

    @Test
    void paymentsOutsideTheScopeAreNotChecked() throws SQLException {
        UUID broken = payment("OUTGOING", "SETTLED", false, 300);
        hold(broken, customer(), "ACTIVE", null);
        UUID mine = payment("OUTGOING", "REJECTED", false, 300);

        assertThat(PaymentInvariants.holdsMatchOutcomes(payments, ledger, List.of(mine))).isEmpty();
        assertThat(PaymentInvariants.csmSettlementPostings(payments, ledger, List.of(mine))).isEmpty();
    }

    @Test
    void theStatesOfThePaymentsAreTheOrchestrators() throws SQLException {
        UUID settled = payment("OUTGOING", "SETTLED", true, 300);
        UUID pending = payment("OUTGOING", "SENT_TO_CSM", false, 300);

        assertThat(PaymentInvariants.states(payments, List.of(settled, pending)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(settled, "SETTLED", pending, "SENT_TO_CSM"));
    }

    private static void assertViolation(List<Violation> violations, String invariant, UUID payment) {
        assertThat(violations).extracting(Violation::invariant, Violation::subject)
                .containsExactly(tuple(invariant, payment.toString()));
    }

    // ---- databases

    private static void migrate(String database, String service, Map<String, String> placeholders) {
        Flyway.configure()
                .dataSource(url(database), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + Path.of("../../services/" + service + "/src/main/resources/db/migration")
                        .toAbsolutePath().normalize())
                .placeholders(placeholders)
                // as the services run them (messaging's Flyway defaults): an index CONCURRENTLY waits forever behind
                // a transactional schema-history lock
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .load()
                .migrate();
    }

    private static String url(String database) {
        return POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
    }

    private static Connection connection(String database) throws SQLException {
        return DriverManager.getConnection(url(database), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    // ---- orchestrator seeding

    private UUID payment(String direction, String state, boolean onUs, long amountMinor) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var statement = payments.prepareStatement("""
                INSERT INTO payments (payment_id, direction, state, on_us, tx_id, uetr, end_to_end_id, amount_minor,
                  currency, debtor_name, debtor_iban, debtor_agent_bic, creditor_name, creditor_iban,
                  creditor_agent_bic, accepted_at, finalised_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'E2E', ?, 'EUR', 'Payer', 'DE00PAYER', 'TPEBDEFFXXX', 'Payee', 'DE00PAYEE',
                  'TPEBDEFFXXX', now(), CASE WHEN ? THEN now() END, now(), now())""")) {
            statement.setObject(1, id);
            statement.setString(2, direction);
            statement.setString(3, state);
            statement.setBoolean(4, onUs);
            statement.setString(5, "TX" + NUMBERS.incrementAndGet());
            statement.setObject(6, UUID.randomUUID());
            statement.setLong(7, amountMinor);
            statement.setBoolean(8, state.equals("SETTLED") || state.equals("REJECTED"));
            statement.executeUpdate();
        }
        return id;
    }

    private void exceptionCase(UUID payment, String type) throws SQLException {
        try (var statement = payments.prepareStatement("""
                INSERT INTO exception_cases (case_id, payment_id, type, status, details, opened_at)
                VALUES (?, ?, ?, 'OPEN', '{}'::jsonb, now())""")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, payment);
            statement.setString(3, type);
            statement.executeUpdate();
        }
    }

    /** {@code SETTLE_ON_US} due {@code ago} in the past. */
    private void pendingAction(UUID payment, Duration ago) throws SQLException {
        try (var statement = payments.prepareStatement("""
                UPDATE payments SET pending_action = 'SETTLE_ON_US', next_action_at = now() - make_interval(secs => ?)
                 WHERE payment_id = ?""")) {
            statement.setDouble(1, ago.toSeconds());
            statement.setObject(2, payment);
            statement.executeUpdate();
        }
    }

    // ---- ledger seeding

    private UUID customer() throws SQLException {
        UUID id = UUID.randomUUID();
        try (var statement = ledger.prepareStatement("""
                INSERT INTO accounts (account_id, iban, kind, customer_id, status, booked_minor, held_minor, opened_at)
                VALUES (?, ?, 'CUSTOMER', ?, 'OPEN', 0, 0, now())""")) {
            statement.setObject(1, id);
            statement.setString(2, "DE00" + "10010010" + NUMBERS.incrementAndGet());
            statement.setString(3, "CUST-" + id);
            statement.executeUpdate();
        }
        return id;
    }

    private UUID csmSettlement() throws SQLException {
        try (var statement = ledger.prepareStatement(
                "SELECT account_id FROM accounts WHERE technical_code = 'CSM_SETTLEMENT'"); var rs = statement.executeQuery()) {
            rs.next();
            return rs.getObject(1, UUID.class);
        }
    }

    private record Posting(UUID account, String side, long amount) {}

    private static Posting posting(UUID account, String side, long amount) {
        return new Posting(account, side, amount);
    }

    private UUID entry(String type, UUID payment, Posting... postings) throws SQLException {
        UUID id = UUID.randomUUID();
        ledger.setAutoCommit(false);
        try {
            try (var statement = ledger.prepareStatement("""
                    INSERT INTO journal_entries (entry_id, entry_type, payment_id, created_at) VALUES (?, ?, ?, now())""")) {
                statement.setObject(1, id);
                statement.setString(2, type);
                statement.setObject(3, payment);
                statement.executeUpdate();
            }
            for (Posting posting : postings) {
                try (var statement = ledger.prepareStatement("""
                        INSERT INTO postings (entry_id, account_id, side, amount_minor, created_at)
                        VALUES (?, ?, ?, ?, now())""")) {
                    statement.setObject(1, id);
                    statement.setObject(2, posting.account());
                    statement.setString(3, posting.side());
                    statement.setLong(4, posting.amount());
                    statement.executeUpdate();
                }
            }
            ledger.commit();
        } finally {
            ledger.setAutoCommit(true);
        }
        return id;
    }

    /** A release that arrived before its hold: the ledger keeps a tombstone (12 §5). */
    private void tombstone(UUID payment) throws SQLException {
        try (var statement = ledger.prepareStatement("""
                INSERT INTO holds (hold_id, status, tombstone, finalised_at, created_at)
                VALUES (?, 'RELEASED', true, now(), now())""")) {
            statement.setObject(1, payment);
            statement.executeUpdate();
        }
    }

    /** The hold of {@code payment}: its id is the payment's, as the orchestrator creates it. */
    private void hold(UUID payment, UUID account, String status, UUID entry) throws SQLException {
        try (var statement = ledger.prepareStatement("""
                INSERT INTO holds (hold_id, account_id, amount_minor, status, payment_id, entry_id, finalised_at,
                  created_at)
                VALUES (?, ?, 100, ?, ?, ?, CASE WHEN ? THEN now() END, now())""")) {
            statement.setObject(1, payment);
            statement.setObject(2, account);
            statement.setString(3, status);
            statement.setObject(4, payment);
            statement.setObject(5, entry);
            statement.setBoolean(6, !status.equals("ACTIVE"));
            statement.executeUpdate();
        }
    }
}
