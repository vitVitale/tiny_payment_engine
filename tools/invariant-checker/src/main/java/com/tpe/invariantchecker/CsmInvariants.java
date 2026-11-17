package com.tpe.invariantchecker;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The invariants of specs/20-testing-strategy.md §3 that hold the CSM side against ours: csm-gateway's database
 * (specs/15 §5) and the simulator's logs (specs/17 §3.5), which the caller reads ({@code GET /sim/v1/received},
 * {@code GET /sim/v1/settlements}) and passes in. It only reads; each check has a negative test.
 *
 * <ul>
 *   <li><b>INV-5</b> no {@code pacs.008} was transmitted after its {@code sendBy}, and none for a transfer the gateway
 *   records as {@code NOT_SENT} (or does not record at all). The CSM logs a receipt, not the transmission's start, so
 *   a receipt within the caller's in-flight tolerance after {@code sendBy} counts as sent in time.</li>
 *   <li><b>INV-10</b> our ledger agrees with the CSM, per payment: every outgoing payment to another PSP whose
 *   transaction the CSM settled has its {@code CSM_SETTLEMENT} posting of the settled amount, and one it did not
 *   settle has none. A settlement of a transaction no payment in scope has is not looked at: the soak's whole-database
 *   form starts from the settlement log instead.</li>
 *   <li><b>INV-11</b>, its gateway part, after quiescence: no transmission is {@code PENDING} past its give-up window
 *   ({@code sendBy}, 15 §6.5) by more than the caller's tolerance.</li>
 * </ul>
 */
public final class CsmInvariants {

    private static final String PACS_008 = "pacs.008";

    private static final String TRANSFERS = """
            SELECT tx_id, send_status, send_by FROM outgoing_transfers WHERE tx_id = ANY (?)""";

    private static final String OUTGOING_TO_PEERS = """
            SELECT payment_id, tx_id, debtor_agent_bic FROM payments
             WHERE payment_id = ANY (?) AND direction = 'OUTGOING' AND NOT on_us ORDER BY payment_id""";

    private static final String CSM_SETTLEMENT_POSTINGS = """
            SELECT e.payment_id, p.side, p.amount_minor
              FROM postings p
              JOIN journal_entries e ON e.entry_id = p.entry_id
              JOIN accounts a ON a.account_id = p.account_id
             WHERE a.technical_code = 'CSM_SETTLEMENT' AND e.payment_id = ANY (?)""";

    private static final String STUCK_TRANSMISSIONS = """
            SELECT tx_id, send_by FROM outgoing_transfers
             WHERE tx_id = ANY (?) AND transmit_status = 'PENDING' AND send_by < now() - make_interval(secs => ?)
             ORDER BY tx_id""";

    private CsmInvariants() {}

    /**
     * A message of the simulator's received log ({@code GET /sim/v1/received}).
     *
     * @param kind {@code RECEIVED} (from the gateway) or {@code SENT} (by the simulator)
     * @param messageType e.g. {@code pacs.008.001.08}
     */
    public record SimulatorMessage(String txId, String kind, String messageType, Instant at) {

        public SimulatorMessage {
            Objects.requireNonNull(txId, "txId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(messageType, "messageType");
            Objects.requireNonNull(at, "at");
        }
    }

    /** An entry of the simulator's settlement log ({@code GET /sim/v1/settlements}). */
    public record Settlement(String txId, String debtorAgentBic, String direction, long amountMinor,
            Instant settledAt) {}

    /**
     * INV-5, over the simulator messages given: the {@code pacs.008} it received from the gateway.
     *
     * @param inFlight how long after its start a transmission may reach the CSM: the gateway's connect timeout
     */
    public static List<Violation> noTransmissionAfterSendBy(Connection gateway, Collection<SimulatorMessage> received,
            Duration inFlight) throws SQLException {
        List<SimulatorMessage> transmissions = received.stream()
                .filter(message -> message.kind().equals("RECEIVED") && message.messageType().startsWith(PACS_008))
                .toList();
        record Transfer(String sendStatus, Instant sendBy) {}
        Map<String, Transfer> transfers = new HashMap<>();
        try (var statement = withArray(gateway, TRANSFERS, "varchar",
                transmissions.stream().map(SimulatorMessage::txId).distinct().toArray());
                var rs = statement.executeQuery()) {
            while (rs.next()) {
                transfers.put(rs.getString("tx_id"), new Transfer(rs.getString("send_status"),
                        rs.getTimestamp("send_by").toInstant()));
            }
        }
        var violations = new ArrayList<Violation>();
        for (SimulatorMessage message : transmissions) {
            Transfer transfer = transfers.get(message.txId());
            if (transfer == null) {
                violations.add(new Violation("INV-5", message.txId(), "pacs.008 received at " + message.at()
                        + " for a transfer the gateway does not record"));
            } else if (transfer.sendStatus().equals("NOT_SENT")) {
                violations.add(new Violation("INV-5", message.txId(), "pacs.008 received at " + message.at()
                        + " for a transfer recorded NOT_SENT"));
            } else if (message.at().isAfter(transfer.sendBy().plus(inFlight))) {
                violations.add(new Violation("INV-5", message.txId(), "pacs.008 received at " + message.at()
                        + ", after its sendBy " + transfer.sendBy() + " and " + inFlight + " in flight"));
            }
        }
        return violations;
    }

    /**
     * INV-10, per payment: for every outgoing payment to another PSP among {@code paymentIds}, the CSM's settlement of
     * its transaction (by {@code txId} and debtor agent) and the ledger's {@code CR CSM_SETTLEMENT} postings agree.
     */
    public static List<Violation> ledgerAgreesWithCsm(Connection payments, Connection ledger,
            Collection<Settlement> settlements, Collection<UUID> paymentIds) throws SQLException {
        Map<String, Long> settled = new HashMap<>();
        for (Settlement settlement : settlements) {
            if (settlement.direction().equals("OUTGOING")) {
                settled.merge(settlement.debtorAgentBic() + ":" + settlement.txId(), settlement.amountMinor(),
                        Long::sum);
            }
        }
        Map<UUID, Long> posted = new HashMap<>();
        try (var statement = withArray(ledger, CSM_SETTLEMENT_POSTINGS, "uuid", paymentIds.toArray());
                var rs = statement.executeQuery()) {
            while (rs.next()) {
                long amount = rs.getLong("amount_minor");
                posted.merge(rs.getObject("payment_id", UUID.class),
                        rs.getString("side").equals("CR") ? amount : -amount, Long::sum);
            }
        }
        var violations = new ArrayList<Violation>();
        try (var statement = withArray(payments, OUTGOING_TO_PEERS, "uuid", paymentIds.toArray());
                var rs = statement.executeQuery()) {
            while (rs.next()) {
                UUID payment = rs.getObject("payment_id", UUID.class);
                Long byCsm = settled.get(rs.getString("debtor_agent_bic") + ":" + rs.getString("tx_id"));
                Long inLedger = posted.get(payment);
                if (!Objects.equals(byCsm, inLedger)) {
                    violations.add(new Violation("INV-10", payment.toString(), "txId " + rs.getString("tx_id")
                            + ": the CSM settled " + (byCsm == null ? "nothing" : byCsm) + ", the ledger posted "
                            + (inLedger == null ? "nothing" : inLedger) + " to CSM_SETTLEMENT"));
                }
            }
        }
        return violations;
    }

    /**
     * INV-11, the gateway part, after quiescence.
     *
     * @param tolerance how long past {@code sendBy} a transmission may still be {@code PENDING}: the gateway's
     *     {@code tpe.csm.transmit.lease} plus its poll interval
     */
    public static List<Violation> gatewayNothingStuck(Connection gateway, Collection<String> txIds,
            Duration tolerance) throws SQLException {
        var violations = new ArrayList<Violation>();
        try (var statement = withArray(gateway, STUCK_TRANSMISSIONS, "varchar", txIds.toArray())) {
            statement.setDouble(2, tolerance.toMillis() / 1000.0);
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    violations.add(new Violation("INV-11", rs.getString("tx_id"), "transmission PENDING, sendBy "
                            + rs.getTimestamp("send_by").toInstant() + " passed by more than " + tolerance));
                }
            }
        }
        return violations;
    }

    private static PreparedStatement withArray(Connection connection, String sql, String type, Object[] values)
            throws SQLException {
        var statement = connection.prepareStatement(sql);
        try {
            statement.setArray(1, connection.createArrayOf(type, values));
            return statement;
        } catch (SQLException | RuntimeException e) {
            statement.close();
            throw e;
        }
    }
}
