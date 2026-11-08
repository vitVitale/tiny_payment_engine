package com.tpe.invariantchecker;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The ledger's global invariants (specs/20-testing-strategy.md §3), SQL over the ledger database
 * (specs/12-ledger-service.md §5). The one implementation the system tests, the soak and the production job run
 * (specs/26-observability.md §12); it only reads.
 *
 * <ul>
 *   <li><b>INV-1</b> every journal entry balances: at least 2 postings and Σ DR = Σ CR;</li>
 *   <li><b>INV-2</b> no customer account has {@code available < 0} or {@code booked < 0};</li>
 *   <li><b>INV-12</b> per customer account {@code booked} = Σ CR − Σ DR of its postings and {@code available} =
 *   {@code booked} − Σ {@code ACTIVE} holds; pending credits change neither, so a balance that counts one breaks the
 *   first equation.</li>
 * </ul>
 */
public final class LedgerInvariants {

    /** The first parameter is true for {@link Scope#all()}; else the account must be in the second one's array. */
    private static final String IN_SCOPE = "(? OR a.account_id = ANY (?))";

    private static final String UNBALANCED_ENTRIES = """
            SELECT e.entry_id, count(p.posting_id) AS postings,
                   coalesce(sum(p.amount_minor) FILTER (WHERE p.side = 'DR'), 0) AS debits,
                   coalesce(sum(p.amount_minor) FILTER (WHERE p.side = 'CR'), 0) AS credits
              FROM journal_entries e
              LEFT JOIN postings p ON p.entry_id = e.entry_id
             WHERE ? OR e.entry_id IN (SELECT entry_id FROM postings WHERE account_id = ANY (?))
             GROUP BY e.entry_id
            HAVING count(p.posting_id) < 2
                OR coalesce(sum(p.amount_minor) FILTER (WHERE p.side = 'DR'), 0)
                   <> coalesce(sum(p.amount_minor) FILTER (WHERE p.side = 'CR'), 0)
             ORDER BY e.entry_id""";

    private static final String NEGATIVE_BALANCES = """
            SELECT a.account_id, a.booked_minor, a.booked_minor - a.held_minor AS available
              FROM accounts a
             WHERE a.kind = 'CUSTOMER' AND (a.booked_minor < 0 OR a.booked_minor - a.held_minor < 0)
               AND """ + IN_SCOPE + " ORDER BY a.account_id";

    private static final String BALANCES_NOT_DERIVED = """
            SELECT a.account_id, a.booked_minor, a.held_minor, p.total AS postings, h.total AS active_holds
              FROM accounts a
             CROSS JOIN LATERAL (SELECT coalesce(sum(CASE side WHEN 'CR' THEN amount_minor ELSE -amount_minor END), 0)
                                   FROM postings WHERE account_id = a.account_id) p (total)
             CROSS JOIN LATERAL (SELECT coalesce(sum(amount_minor), 0)
                                   FROM holds WHERE account_id = a.account_id AND status = 'ACTIVE') h (total)
             WHERE a.kind = 'CUSTOMER' AND (a.booked_minor <> p.total OR a.held_minor <> h.total)
               AND """ + IN_SCOPE + " ORDER BY a.account_id";

    private LedgerInvariants() {}

    /** The breaches of INV-1, INV-2 and INV-12 within {@code scope}; empty when all hold. */
    public static List<Violation> check(Connection ledger, Scope scope) throws SQLException {
        var violations = new ArrayList<Violation>();
        violations.addAll(entriesBalance(ledger, scope));
        violations.addAll(noNegativeBalances(ledger, scope));
        violations.addAll(balancesMatchPostingsAndHolds(ledger, scope));
        return violations;
    }

    /** INV-1. */
    public static List<Violation> entriesBalance(Connection ledger, Scope scope) throws SQLException {
        var violations = new ArrayList<Violation>();
        try (var statement = scoped(ledger, UNBALANCED_ENTRIES, scope); var rs = statement.executeQuery()) {
            while (rs.next()) {
                violations.add(new Violation("INV-1", rs.getString("entry_id"),
                        "%d posting(s), DR %d, CR %d".formatted(
                                rs.getLong("postings"), rs.getLong("debits"), rs.getLong("credits"))));
            }
        }
        return violations;
    }

    /** INV-2. */
    public static List<Violation> noNegativeBalances(Connection ledger, Scope scope) throws SQLException {
        var violations = new ArrayList<Violation>();
        try (var statement = scoped(ledger, NEGATIVE_BALANCES, scope); var rs = statement.executeQuery()) {
            while (rs.next()) {
                violations.add(new Violation("INV-2", rs.getString("account_id"),
                        "booked %d, available %d".formatted(rs.getLong("booked_minor"), rs.getLong("available"))));
            }
        }
        return violations;
    }

    /** INV-12. */
    public static List<Violation> balancesMatchPostingsAndHolds(Connection ledger, Scope scope) throws SQLException {
        var violations = new ArrayList<Violation>();
        try (var statement = scoped(ledger, BALANCES_NOT_DERIVED, scope); var rs = statement.executeQuery()) {
            while (rs.next()) {
                violations.add(new Violation("INV-12", rs.getString("account_id"),
                        "booked %d vs Σ postings %d, held %d vs Σ active holds %d".formatted(
                                rs.getLong("booked_minor"), rs.getLong("postings"),
                                rs.getLong("held_minor"), rs.getLong("active_holds"))));
            }
        }
        return violations;
    }

    private static PreparedStatement scoped(Connection ledger, String sql, Scope scope) throws SQLException {
        var statement = ledger.prepareStatement(sql);
        try {
            var ids = scope.accountIds();
            statement.setBoolean(1, ids.isEmpty());
            statement.setArray(2, ledger.createArrayOf("uuid", ids.orElse(List.of()).toArray(UUID[]::new)));
            return statement;
        } catch (SQLException | RuntimeException e) {
            statement.close();
            throw e;
        }
    }
}
