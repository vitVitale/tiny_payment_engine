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
import java.util.Set;
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
 *   <li><b>INV-6</b> exactly one answer {@code pacs.002} per incoming transaction reached the CSM, and the
 *   orchestrator's state agrees with it.</li>
 *   <li><b>INV-10</b> our ledger agrees with the CSM, per payment: every payment to or from another PSP whose
 *   transaction the CSM settled has its {@code CSM_SETTLEMENT} posting of the settled amount, and one it did not
 *   settle has none. A settlement of a transaction no payment in scope has is not looked at: the soak's whole-database
 *   form starts from the settlement log instead.</li>
 *   <li><b>INV-11</b>, its gateway part, after quiescence: no transmission is {@code PENDING} past its give-up window
 *   ({@code sendBy}, 15 §6.5) by more than the caller's tolerance; no incoming transfer is unanswered, or its answer
 *   {@code PENDING}, that long past its answer deadline.</li>
 * </ul>
 */
public final class CsmInvariants {

    private static final String PACS_008 = "pacs.008";

    private static final String TRANSFERS = """
            SELECT tx_id, send_status, send_by FROM outgoing_transfers WHERE tx_id = ANY (?)""";

    /** The payments the CSM may have settled: to another PSP, or from one. */
    private static final String THROUGH_THE_CSM = """
            SELECT payment_id, direction, tx_id, debtor_agent_bic FROM payments
             WHERE payment_id = ANY (?) AND NOT on_us ORDER BY payment_id""";

    private static final String INCOMING = """
            SELECT payment_id, tx_id, debtor_agent_bic, state, reason_code, csm_outcome FROM payments
             WHERE payment_id = ANY (?) AND direction = 'INCOMING' ORDER BY payment_id""";

    private static final String STUCK_INCOMING = """
            SELECT debtor_agent_bic, tx_id, answer_status, transmit_status, answer_deadline FROM incoming_transfers
             WHERE debtor_agent_bic || ':' || tx_id = ANY (?)
               AND (answer_status IS NULL OR transmit_status = 'PENDING')
               AND answer_deadline < now() - make_interval(secs => ?)
             ORDER BY tx_id""";

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

    /**
     * An answer {@code pacs.002} the CSM received from the gateway for an incoming transaction (its received log).
     *
     * @param msgId the answer's own; the gateway re-sends the same answer, same {@code MsgId}, for a duplicate
     *     {@code pacs.008}
     * @param reason the {@code StsRsnInf/Rsn/Cd} of a {@code RJCT}; {@code null} for {@code ACCP}
     */
    public record Answer(String debtorAgentBic, String txId, String msgId, String status, String reason) {

        public Answer {
            Objects.requireNonNull(debtorAgentBic, "debtorAgentBic");
            Objects.requireNonNull(txId, "txId");
            Objects.requireNonNull(msgId, "msgId");
            Objects.requireNonNull(status, "status");
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
     * INV-10, per payment: for every payment among {@code paymentIds} that goes through the CSM (to or from another
     * PSP), the CSM's settlement of its transaction (by direction, debtor agent and {@code txId}) and the ledger's
     * {@code CSM_SETTLEMENT} postings agree: an outgoing one's {@code CR}, an incoming one's {@code DR}.
     */
    public static List<Violation> ledgerAgreesWithCsm(Connection payments, Connection ledger,
            Collection<Settlement> settlements, Collection<UUID> paymentIds) throws SQLException {
        Map<String, Long> settled = new HashMap<>();
        for (Settlement settlement : settlements) {
            settled.merge(settlement.direction() + ":" + settlement.debtorAgentBic() + ":" + settlement.txId(),
                    settlement.amountMinor(), Long::sum);
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
        try (var statement = withArray(payments, THROUGH_THE_CSM, "uuid", paymentIds.toArray());
                var rs = statement.executeQuery()) {
            while (rs.next()) {
                UUID payment = rs.getObject("payment_id", UUID.class);
                String direction = rs.getString("direction");
                Long byCsm = settled.get(direction + ":" + rs.getString("debtor_agent_bic") + ":"
                        + rs.getString("tx_id"));
                Long credited = posted.get(payment);
                // the CSM settlement account is credited for money out, debited for money in
                Long inLedger = credited == null ? null : direction.equals("OUTGOING") ? credited : -credited;
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
     * INV-6, per payment: every incoming payment among {@code paymentIds} has exactly one answer the CSM received
     * (distinct by its {@code MsgId}; a re-sent one must say the same), and the orchestrator follows it: {@code ACCP} → accepted on ({@code ACCEPTED},
     * {@code INVESTIGATING}, {@code SETTLED}) or rejected by the CSM afterwards; {@code RJCT} → {@code REJECTED} with
     * its reason and no CSM outcome.
     *
     * @param answers the answer {@code pacs.002} the CSM received for the payments' transactions
     */
    public static List<Violation> oneAnswerPerIncoming(Connection payments, Collection<Answer> answers,
            Collection<UUID> paymentIds) throws SQLException {
        Map<String, Map<String, Answer>> byTransaction = new HashMap<>();
        var violations = new ArrayList<Violation>();
        for (Answer answer : answers) {
            Answer first = byTransaction.computeIfAbsent(answer.debtorAgentBic() + ":" + answer.txId(),
                    key -> new HashMap<>()).putIfAbsent(answer.msgId(), answer);
            if (first != null && !first.equals(answer)) {
                // a re-sent answer is the same message: one MsgId saying two things is two answers
                violations.add(new Violation("INV-6", answer.debtorAgentBic() + ":" + answer.txId(), "answer "
                        + answer.msgId() + " reached the CSM as " + first.status() + " and as " + answer.status()));
            }
        }
        try (var statement = withArray(payments, INCOMING, "uuid", paymentIds.toArray());
                var rs = statement.executeQuery()) {
            while (rs.next()) {
                String subject = rs.getObject("payment_id", UUID.class).toString();
                String transaction = rs.getString("debtor_agent_bic") + ":" + rs.getString("tx_id");
                var found = byTransaction.getOrDefault(transaction, Map.of()).values();
                if (found.size() != 1) {
                    violations.add(new Violation("INV-6", subject, transaction + ": " + found.size()
                            + " answers reached the CSM " + found));
                    continue;
                }
                Answer answer = found.iterator().next();
                String state = rs.getString("state");
                String reason = rs.getString("reason_code");
                boolean csmRejected = "REJECTED".equals(rs.getString("csm_outcome"));
                if (!agrees(answer, state, reason, csmRejected)) {
                    violations.add(new Violation("INV-6", subject, transaction + ": answered " + answer.status()
                            + (answer.reason() == null ? "" : " " + answer.reason()) + ", the payment is " + state
                            + (reason == null ? "" : " " + reason) + (csmRejected ? " (rejected by the CSM)" : "")));
                }
            }
        }
        return violations;
    }

    /**
     * The orchestrator follows the answer: an accepting one, accepted on or rejected by the CSM afterwards; a rejecting
     * one, {@code REJECTED} with its reason and by no CSM outcome.
     */
    private static boolean agrees(Answer answer, String state, String reason, boolean csmRejected) {
        if (answer.status().equals("ACCP")) {
            return Set.of("ACCEPTED", "INVESTIGATING", "SETTLED").contains(state)
                    || (state.equals("REJECTED") && csmRejected);
        }
        return state.equals("REJECTED") && !csmRejected && Objects.equals(reason, answer.reason());
    }

    /**
     * INV-11, the gateway part for incoming transfers, after quiescence: none is unanswered, or has its answer
     * {@code PENDING}, past its {@code answer_deadline} by more than the caller's tolerance.
     *
     * @param transactions {@code <debtorAgentBic>:<txId>} of each
     * @param tolerance the gateway's {@code tpe.csm.answer-transmit-window} (the answer is sent until then) plus its
     *     transmit lease and transmit poll interval
     */
    public static List<Violation> gatewayIncomingNothingStuck(Connection gateway, Collection<String> transactions,
            Duration tolerance) throws SQLException {
        var violations = new ArrayList<Violation>();
        try (var statement = withArray(gateway, STUCK_INCOMING, "varchar", transactions.toArray())) {
            statement.setDouble(2, tolerance.toMillis() / 1000.0);
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    String transaction = rs.getString("debtor_agent_bic") + ":" + rs.getString("tx_id");
                    String deadline = rs.getTimestamp("answer_deadline").toInstant().toString();
                    violations.add(new Violation("INV-11", transaction, rs.getString("answer_status") == null
                            ? "unanswered, answer deadline " + deadline + " passed by more than " + tolerance
                            : "answer " + rs.getString("transmit_status") + ", answer deadline " + deadline
                                    + " passed by more than " + tolerance));
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
