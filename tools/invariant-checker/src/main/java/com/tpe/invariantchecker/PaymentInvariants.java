package com.tpe.invariantchecker;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The payment invariants of specs/20-testing-strategy.md §3 that join the orchestrator database (specs/11 §5) with the
 * ledger's (specs/12 §5), for the payments given: a system test's own (per-test) or every payment (the soak). It only
 * reads; each check has a negative test.
 *
 * <ul>
 *   <li><b>INV-3</b> a final outgoing payment has no {@code ACTIVE} hold; {@code SETTLED} ⇔ its hold {@code SETTLED}
 *   (an on-us payment's by an {@code ON_US} entry, one to another PSP's by a {@code PAYMENT_OUT} entry to the
 *   CSM); {@code REJECTED} ⇔ its hold {@code RELEASED}, tombstoned or never created. The hold id is the payment id
 *   (orchestrator {@code HttpLedger}).</li>
 *   <li><b>INV-4</b> a final incoming payment has no {@code ACTIVE} pending credit; {@code SETTLED} ⇔ its pending
 *   credit {@code CONFIRMED}; {@code REJECTED} ⇔ it {@code CANCELLED}, tombstoned or never created. The pending credit
 *   id is the payment id (ADR-010).</li>
 *   <li><b>INV-8</b> a {@code SETTLED} outgoing payment that is not on-us has exactly one {@code CR CSM_SETTLEMENT}
 *   posting of its amount, a {@code SETTLED} incoming payment exactly one {@code DR CSM_SETTLEMENT}; on-us and
 *   {@code REJECTED} payments none.</li>
 *   <li><b>INV-9</b>, its case part: no exception case of a type the caller did not expect (the DLT part is
 *   {@link EventInvariants#noDeadLetters}).</li>
 *   <li><b>INV-11</b>, its orchestrator part, after quiescence: every payment is final or has an {@code OPEN} case;
 *   no {@code SCHEDULED} timer belongs to a final payment; no {@code pending_action} is overdue by more than
 *   {@code lease + max-backoff}.</li>
 * </ul>
 */
public final class PaymentInvariants {

    private static final Set<String> FINAL = Set.of("SETTLED", "REJECTED");

    private static final String PAYMENTS = """
            SELECT payment_id, direction, state, on_us, amount_minor FROM payments
             WHERE payment_id = ANY (?) ORDER BY payment_id""";

    private static final String HOLDS = """
            SELECT h.hold_id, h.status, e.entry_type
              FROM holds h LEFT JOIN journal_entries e ON e.entry_id = h.entry_id
             WHERE h.hold_id = ANY (?)""";

    private static final String PENDING_CREDITS = """
            SELECT pending_credit_id, status FROM pending_credits WHERE pending_credit_id = ANY (?)""";

    private static final String CSM_SETTLEMENT_POSTINGS = """
            SELECT e.payment_id, p.side, p.amount_minor
              FROM postings p
              JOIN journal_entries e ON e.entry_id = p.entry_id
              JOIN accounts a ON a.account_id = p.account_id
             WHERE a.technical_code = 'CSM_SETTLEMENT' AND e.payment_id = ANY (?)""";

    private static final String WITHOUT_OPEN_CASE = """
            SELECT p.payment_id, p.state FROM payments p
             WHERE p.payment_id = ANY (?) AND p.state NOT IN ('SETTLED', 'REJECTED')
               AND NOT EXISTS (SELECT 1 FROM exception_cases c WHERE c.payment_id = p.payment_id AND c.status = 'OPEN')
             ORDER BY p.payment_id""";

    private static final String TIMERS_OF_FINAL_PAYMENTS = """
            SELECT t.payment_id, t.type, t.timer_id FROM payment_timers t JOIN payments p USING (payment_id)
             WHERE t.payment_id = ANY (?) AND t.status = 'SCHEDULED' AND p.state IN ('SETTLED', 'REJECTED')
             ORDER BY t.timer_id""";

    private static final String OVERDUE_ACTIONS = """
            SELECT payment_id, pending_action, next_action_at FROM payments
             WHERE payment_id = ANY (?) AND pending_action IS NOT NULL
               AND next_action_at < now() - make_interval(secs => ?)
             ORDER BY payment_id""";

    private static final String CASES = """
            SELECT case_id, payment_id, type FROM exception_cases WHERE payment_id = ANY (?) ORDER BY case_id""";

    private PaymentInvariants() {}

    /** A payment as the orchestrator keeps it, as far as the checks need it. */
    private record Payment(UUID id, String direction, String state, boolean onUs, long amountMinor) {

        boolean isFinal() {
            return FINAL.contains(state);
        }
    }

    /** A tombstoned hold is {@code RELEASED} by the ledger's schema, so its status says all INV-3 needs. */
    private record Hold(String status, String entryType) {}

    /** INV-3. */
    public static List<Violation> holdsMatchOutcomes(Connection payments, Connection ledger,
            Collection<UUID> paymentIds) throws SQLException {
        Map<UUID, Hold> holds = new HashMap<>();
        try (var statement = withIds(ledger, HOLDS, paymentIds); var rs = statement.executeQuery()) {
            while (rs.next()) {
                holds.put(rs.getObject("hold_id", UUID.class), new Hold(rs.getString("status"),
                        rs.getString("entry_type")));
            }
        }
        var violations = new ArrayList<Violation>();
        for (Payment payment : payments(payments, paymentIds)) {
            if (!payment.direction().equals("OUTGOING") || !payment.isFinal()) {
                continue;
            }
            Hold hold = holds.get(payment.id());
            String subject = payment.id().toString();
            if (hold != null && hold.status().equals("ACTIVE")) {
                violations.add(new Violation("INV-3", subject, payment.state() + " with an ACTIVE hold"));
            } else if (payment.state().equals("SETTLED")) {
                if (hold == null || !hold.status().equals("SETTLED")) {
                    violations.add(new Violation("INV-3", subject, "SETTLED, hold "
                            + (hold == null ? "never created" : hold.status())));
                } else if (payment.onUs() && !"ON_US".equals(hold.entryType())) {
                    violations.add(new Violation("INV-3", subject, "on-us SETTLED, hold settled by a "
                            + hold.entryType() + " entry"));
                } else if (!payment.onUs() && !"PAYMENT_OUT".equals(hold.entryType())) {
                    violations.add(new Violation("INV-3", subject, "SETTLED to another PSP, hold settled by a "
                            + hold.entryType() + " entry, not a PAYMENT_OUT one"));
                }
            } else if (hold != null && !hold.status().equals("RELEASED")) {
                violations.add(new Violation("INV-3", subject, "REJECTED, hold " + hold.status()));
            }
        }
        return violations;
    }

    /** INV-4. A tombstone is {@code CANCELLED} by the ledger's schema, so its status says all INV-4 needs. */
    public static List<Violation> pendingCreditsMatchOutcomes(Connection payments, Connection ledger,
            Collection<UUID> paymentIds) throws SQLException {
        Map<UUID, String> credits = new HashMap<>();
        try (var statement = withIds(ledger, PENDING_CREDITS, paymentIds); var rs = statement.executeQuery()) {
            while (rs.next()) {
                credits.put(rs.getObject("pending_credit_id", UUID.class), rs.getString("status"));
            }
        }
        var violations = new ArrayList<Violation>();
        for (Payment payment : payments(payments, paymentIds)) {
            if (!payment.direction().equals("INCOMING") || !payment.isFinal()) {
                continue;
            }
            String credit = credits.get(payment.id());
            String subject = payment.id().toString();
            if ("ACTIVE".equals(credit)) {
                violations.add(new Violation("INV-4", subject, payment.state() + " with an ACTIVE pending credit"));
            } else if (payment.state().equals("SETTLED") && !"CONFIRMED".equals(credit)) {
                violations.add(new Violation("INV-4", subject, "SETTLED, pending credit "
                        + (credit == null ? "never created" : credit)));
            } else if (payment.state().equals("REJECTED") && credit != null && !credit.equals("CANCELLED")) {
                violations.add(new Violation("INV-4", subject, "REJECTED, pending credit " + credit));
            }
        }
        return violations;
    }

    /** INV-8, per payment. */
    public static List<Violation> csmSettlementPostings(Connection payments, Connection ledger,
            Collection<UUID> paymentIds) throws SQLException {
        Map<UUID, List<String>> postings = new HashMap<>();
        try (var statement = withIds(ledger, CSM_SETTLEMENT_POSTINGS, paymentIds); var rs = statement.executeQuery()) {
            while (rs.next()) {
                postings.computeIfAbsent(rs.getObject("payment_id", UUID.class), id -> new ArrayList<>())
                        .add(rs.getString("side") + " " + rs.getLong("amount_minor"));
            }
        }
        var violations = new ArrayList<Violation>();
        for (Payment payment : payments(payments, paymentIds)) {
            if (!payment.isFinal()) {
                continue;
            }
            List<String> found = postings.getOrDefault(payment.id(), List.of());
            List<String> expected = !payment.state().equals("SETTLED") || payment.onUs() ? List.of()
                    : List.of((payment.direction().equals("OUTGOING") ? "CR " : "DR ") + payment.amountMinor());
            if (!found.equals(expected)) {
                violations.add(new Violation("INV-8", payment.id().toString(), "%s %s%s: CSM_SETTLEMENT postings %s, "
                        .formatted(payment.state(), payment.direction(), payment.onUs() ? " on-us" : "", found)
                        + "expected " + expected));
            }
        }
        return violations;
    }

    /** INV-9, the case part: every exception case of these payments whose type is not one of {@code expected}. */
    public static List<Violation> noUnexpectedCases(Connection payments, Collection<UUID> paymentIds,
            Set<String> expected) throws SQLException {
        var violations = new ArrayList<Violation>();
        try (var statement = withIds(payments, CASES, paymentIds); var rs = statement.executeQuery()) {
            while (rs.next()) {
                if (!expected.contains(rs.getString("type"))) {
                    violations.add(new Violation("INV-9", rs.getString("payment_id"),
                            "unexpected " + rs.getString("type") + " case " + rs.getString("case_id")));
                }
            }
        }
        return violations;
    }

    /**
     * INV-11, the orchestrator part, after quiescence.
     *
     * @param tolerance {@code tpe.runner.lease + tpe.runner.max-backoff} of the orchestrator
     */
    public static List<Violation> nothingStuck(Connection payments, Collection<UUID> paymentIds, Duration tolerance)
            throws SQLException {
        var violations = new ArrayList<Violation>();
        try (var statement = withIds(payments, WITHOUT_OPEN_CASE, paymentIds); var rs = statement.executeQuery()) {
            while (rs.next()) {
                violations.add(new Violation("INV-11", rs.getString("payment_id"),
                        rs.getString("state") + ", not final and no OPEN case"));
            }
        }
        try (var statement = withIds(payments, TIMERS_OF_FINAL_PAYMENTS, paymentIds);
                var rs = statement.executeQuery()) {
            while (rs.next()) {
                violations.add(new Violation("INV-11", rs.getString("payment_id"),
                        "final, SCHEDULED " + rs.getString("type") + " timer " + rs.getLong("timer_id")));
            }
        }
        try (var statement = withIds(payments, OVERDUE_ACTIONS, paymentIds)) {
            statement.setDouble(2, tolerance.toMillis() / 1000.0);
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    violations.add(new Violation("INV-11", rs.getString("payment_id"), rs.getString("pending_action")
                            + " due " + rs.getString("next_action_at") + ", overdue by more than " + tolerance));
                }
            }
        }
        return violations;
    }

    /** The orchestrator's state of each payment, for {@link EventInvariants#finalEvents}. */
    public static Map<UUID, String> states(Connection payments, Collection<UUID> paymentIds) throws SQLException {
        var states = new HashMap<UUID, String>();
        for (Payment payment : payments(payments, paymentIds)) {
            states.put(payment.id(), payment.state());
        }
        return states;
    }

    private static List<Payment> payments(Connection payments, Collection<UUID> paymentIds) throws SQLException {
        var found = new ArrayList<Payment>();
        try (var statement = withIds(payments, PAYMENTS, paymentIds); var rs = statement.executeQuery()) {
            while (rs.next()) {
                found.add(new Payment(rs.getObject("payment_id", UUID.class), rs.getString("direction"),
                        rs.getString("state"), rs.getBoolean("on_us"), rs.getLong("amount_minor")));
            }
        }
        return found;
    }

    private static PreparedStatement withIds(Connection connection, String sql, Collection<UUID> ids)
            throws SQLException {
        var statement = connection.prepareStatement(sql);
        try {
            statement.setArray(1, connection.createArrayOf("uuid", ids.toArray(UUID[]::new)));
            return statement;
        } catch (SQLException | RuntimeException e) {
            statement.close();
            throw e;
        }
    }
}
