package com.tpe.invariantchecker;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.invariantchecker.CsmInvariants.Answer;
import com.tpe.invariantchecker.CsmInvariants.SimulatorMessage;
import com.tpe.invariantchecker.CsmInvariants.Settlement;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * INV-5, INV-6, INV-10 and INV-11 (gateway part) of specs/20-testing-strategy.md §3 against the schemas of csm-gateway's,
 * payment-orchestrator's and ledger-service's own migrations, each in its own database as in the compose stack, with
 * the simulator's logs (17 §3.5) as the checks receive them. Each check has a negative test (20 §3).
 */
class CsmInvariantsIT {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");
    private static final AtomicLong NUMBERS = new AtomicLong(9_000_000_000L);
    private static final String OUR_BIC = "TPEBDEFFXXX";
    private static final String PACS_008 = "pacs.008.001.08";
    private static final Duration TOLERANCE = Duration.ofSeconds(3);
    private static final Duration IN_FLIGHT = Duration.ofMillis(200);

    static {
        POSTGRES.start();
        try (var admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE csm");
            statement.execute("CREATE DATABASE payments");
            statement.execute("CREATE DATABASE ledger");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        migrate("csm", "csm-gateway", Map.of());
        migrate("payments", "payment-orchestrator", Map.of());
        migrate("ledger", "ledger-service", Map.of("bankCode", "10010010"));
    }

    private Connection gateway;
    private Connection payments;
    private Connection ledger;

    @BeforeEach
    void connect() throws SQLException {
        gateway = connection("csm");
        payments = connection("payments");
        ledger = connection("ledger");
    }

    @AfterEach
    void disconnect() throws SQLException {
        gateway.close();
        payments.close();
        ledger.close();
    }

    // ---- INV-5

    @Test
    void transmissionsBeforeSendByPassInv5() throws SQLException {
        Instant sendBy = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String sent = transfer("SENT", sendBy, "DELIVERED");
        String notSent = transfer("NOT_SENT", sendBy, null);

        String justInTime = transfer("SENT", sendBy, "DELIVERED");

        assertThat(CsmInvariants.noTransmissionAfterSendBy(gateway, List.of(
                received(sent, PACS_008, sendBy.minusMillis(1)),
                // started before sendBy, received within the in-flight tolerance after it
                received(justInTime, PACS_008, sendBy.plus(IN_FLIGHT)),
                // the simulator's own answer, and a pacs.028: not a transmission of the transfer
                new SimulatorMessage(sent, "SENT", "pacs.002.001.10", sendBy.plusSeconds(1)),
                received(notSent, "pacs.028.001.03", sendBy.plusSeconds(5))), IN_FLIGHT)).isEmpty();
    }

    @Test
    void INV_5_aPacs008ReceivedAfterItsSendByFails() throws SQLException {
        Instant sendBy = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String txId = transfer("SENT", sendBy, "DELIVERED");

        assertViolation(CsmInvariants.noTransmissionAfterSendBy(gateway,
                List.of(received(txId, PACS_008, sendBy.plus(IN_FLIGHT).plusMillis(1))), IN_FLIGHT), "INV-5", txId);
    }

    @Test
    void INV_5_aPacs008OfATransferRecordedNotSentFails() throws SQLException {
        Instant sendBy = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String txId = transfer("NOT_SENT", sendBy, null);

        assertViolation(CsmInvariants.noTransmissionAfterSendBy(gateway,
                List.of(received(txId, PACS_008, sendBy.minusSeconds(1))), IN_FLIGHT), "INV-5", txId);
    }

    @Test
    void INV_5_aPacs008TheGatewayNeverRecordedFails() throws SQLException {
        String txId = "TX" + NUMBERS.incrementAndGet();

        assertViolation(CsmInvariants.noTransmissionAfterSendBy(gateway,
                List.of(received(txId, PACS_008, Instant.now())), IN_FLIGHT), "INV-5", txId);
    }

    // ---- INV-10

    @Test
    void aLedgerThatAgreesWithTheCsmPassesInv10() throws SQLException {
        UUID settled = payment("SETTLED", false, 500);
        entry(settled, 500);
        UUID rejected = payment("REJECTED", false, 700);
        UUID onUs = payment("SETTLED", true, 300);

        assertThat(CsmInvariants.ledgerAgreesWithCsm(payments, ledger,
                List.of(settlement(settled, 500)), List.of(settled, rejected, onUs))).isEmpty();
    }

    @Test
    void INV_10_aTransferTheCsmSettledWithoutItsLedgerPostingFails() throws SQLException {
        UUID payment = payment("SETTLED", false, 500);

        assertViolation(CsmInvariants.ledgerAgreesWithCsm(payments, ledger, List.of(settlement(payment, 500)),
                List.of(payment)), "INV-10", payment.toString());
    }

    @Test
    void INV_10_aLedgerPostingForATransferTheCsmDidNotSettleFails() throws SQLException {
        UUID payment = payment("SETTLED", false, 500);
        entry(payment, 500);

        assertViolation(CsmInvariants.ledgerAgreesWithCsm(payments, ledger, List.of(), List.of(payment)), "INV-10",
                payment.toString());
    }

    @Test
    void INV_10_aSettlementOfAnotherAmountThanThePostingFails() throws SQLException {
        UUID payment = payment("SETTLED", false, 500);
        entry(payment, 500);

        assertViolation(CsmInvariants.ledgerAgreesWithCsm(payments, ledger, List.of(settlement(payment, 499)),
                List.of(payment)), "INV-10", payment.toString());
    }

    @Test
    void anIncomingSettlementTheLedgerBookedPassesInv10() throws SQLException {
        UUID settled = incomingPayment("SETTLED", null, null, 400);
        incomingEntry(settled, 400);
        UUID rejected = incomingPayment("REJECTED", "AC01", null, 400);

        assertThat(CsmInvariants.ledgerAgreesWithCsm(payments, ledger, List.of(incomingSettlement(settled, 400)),
                List.of(settled, rejected))).isEmpty();
    }

    @Test
    void INV_10_anIncomingTransferTheCsmSettledWithoutItsLedgerPostingFails() throws SQLException {
        UUID payment = incomingPayment("ACCEPTED", null, null, 400);

        assertViolation(CsmInvariants.ledgerAgreesWithCsm(payments, ledger, List.of(incomingSettlement(payment, 400)),
                List.of(payment)), "INV-10", payment.toString());
    }

    @Test
    void INV_10_anIncomingPostingForATransferTheCsmDidNotSettleFails() throws SQLException {
        UUID payment = incomingPayment("SETTLED", null, null, 400);
        incomingEntry(payment, 400);

        assertViolation(CsmInvariants.ledgerAgreesWithCsm(payments, ledger, List.of(), List.of(payment)), "INV-10",
                payment.toString());
    }

    // ---- INV-6

    @Test
    void oneAnswerTheOrchestratorFollowsPassesInv6() throws SQLException {
        UUID settled = incomingPayment("SETTLED", null, "SETTLED", 100);
        UUID rejectedByCsm = incomingPayment("REJECTED", "AB05", "REJECTED", 100);
        UUID refused = incomingPayment("REJECTED", "AC01", null, 100);

        assertThat(CsmInvariants.oneAnswerPerIncoming(payments, List.of(
                answer(settled, "M1", "ACCP", null),
                answer(rejectedByCsm, "M2", "ACCP", null),
                answer(refused, "M3", "RJCT", "AC01"),
                // the same answer re-sent for a duplicate pacs.008 (E2E-I-10): one answer
                answer(refused, "M3", "RJCT", "AC01")), List.of(settled, rejectedByCsm, refused))).isEmpty();
    }

    @Test
    void INV_6_twoAnswersForOneIncomingTransferFail() throws SQLException {
        UUID payment = incomingPayment("SETTLED", null, "SETTLED", 100);

        assertViolation(CsmInvariants.oneAnswerPerIncoming(payments, List.of(answer(payment, "M1", "ACCP", null),
                answer(payment, "M2", "RJCT", "AB05")), List.of(payment)), "INV-6", payment.toString());
    }

    @Test
    void INV_6_anIncomingTransferNeverAnsweredFails() throws SQLException {
        UUID payment = incomingPayment("REJECTED", "AB05", null, 100);

        assertViolation(CsmInvariants.oneAnswerPerIncoming(payments, List.of(), List.of(payment)), "INV-6",
                payment.toString());
    }

    @Test
    void INV_6_anAcceptingAnswerForARejectedPaymentFails() throws SQLException {
        UUID payment = incomingPayment("REJECTED", "AC01", null, 100);

        assertViolation(CsmInvariants.oneAnswerPerIncoming(payments, List.of(answer(payment, "M1", "ACCP", null)),
                List.of(payment)), "INV-6", payment.toString());
    }

    @Test
    void INV_6_aRejectingAnswerWithAnotherReasonThanThePaymentsFails() throws SQLException {
        UUID payment = incomingPayment("REJECTED", "AC01", null, 100);

        assertViolation(CsmInvariants.oneAnswerPerIncoming(payments, List.of(answer(payment, "M1", "RJCT", "AB05")),
                List.of(payment)), "INV-6", payment.toString());
    }

    @Test
    void INV_6_oneAnswerReachingTheCsmWithTwoStatusesFails() throws SQLException {
        UUID payment = incomingPayment("SETTLED", null, "SETTLED", 100);

        assertViolation(CsmInvariants.oneAnswerPerIncoming(payments, List.of(answer(payment, "M1", "ACCP", null),
                answer(payment, "M1", "RJCT", "AB05")), List.of(payment)), "INV-6", "PEERDEFFXXX:" + txIdOf(payment));
    }

    @Test
    void INV_6_aRejectingAnswerForAPaymentTheCsmRejectedFails() throws SQLException {
        UUID payment = incomingPayment("REJECTED", "AB05", "REJECTED", 100);

        assertViolation(CsmInvariants.oneAnswerPerIncoming(payments, List.of(answer(payment, "M1", "RJCT", "AB05")),
                List.of(payment)), "INV-6", payment.toString());
    }

    @Test
    void INV_6_anAcceptingAnswerForAPaymentStillAwaitingItFails() throws SQLException {
        UUID payment = incomingPayment("ACCEPT_REQUESTED", null, null, 100);

        assertViolation(CsmInvariants.oneAnswerPerIncoming(payments, List.of(answer(payment, "M1", "ACCP", null)),
                List.of(payment)), "INV-6", payment.toString());
    }

    @Test
    void INV_6_aRejectingAnswerForASettledPaymentFails() throws SQLException {
        UUID payment = incomingPayment("SETTLED", null, "SETTLED", 100);

        assertViolation(CsmInvariants.oneAnswerPerIncoming(payments, List.of(answer(payment, "M1", "RJCT", "AB05")),
                List.of(payment)), "INV-6", payment.toString());
    }

    // ---- INV-11, the gateway part

    @Test
    void anIncomingTransferAnsweredAndDeliveredPassesInv11() throws SQLException {
        String delivered = incomingTransfer(Instant.now().minusSeconds(60), "ACCP", "DELIVERED");
        String withinItsWindow = incomingTransfer(Instant.now(), null, null);

        assertThat(CsmInvariants.gatewayIncomingNothingStuck(gateway, List.of(delivered, withinItsWindow),
                TOLERANCE)).isEmpty();
    }

    @Test
    void INV_11_anIncomingTransferUnansweredPastItsDeadlineFails() throws SQLException {
        String transaction = incomingTransfer(Instant.now().minus(TOLERANCE).minusSeconds(1), null, null);

        assertViolation(CsmInvariants.gatewayIncomingNothingStuck(gateway, List.of(transaction), TOLERANCE),
                "INV-11", transaction);
    }

    @Test
    void INV_11_anAnswerPendingPastItsTransmitWindowFails() throws SQLException {
        String transaction = incomingTransfer(Instant.now().minus(TOLERANCE).minusSeconds(1), "ACCP", "PENDING");

        assertViolation(CsmInvariants.gatewayIncomingNothingStuck(gateway, List.of(transaction), TOLERANCE),
                "INV-11", transaction);
    }

    @Test
    void aPendingTransmissionWithinItsWindowPassesInv11() throws SQLException {
        String txId = transfer("SENT", Instant.now().plusSeconds(10), "PENDING");

        assertThat(CsmInvariants.gatewayNothingStuck(gateway, List.of(txId), TOLERANCE)).isEmpty();
    }

    @Test
    void INV_11_aTransmissionPendingPastItsGiveUpWindowFails() throws SQLException {
        String txId = transfer("SENT", Instant.now().minus(TOLERANCE).minusSeconds(1), "PENDING");

        assertViolation(CsmInvariants.gatewayNothingStuck(gateway, List.of(txId), TOLERANCE), "INV-11", txId);
    }

    @Test
    void transfersOutsideTheScopeAreNotChecked() throws SQLException {
        String stuck = transfer("SENT", Instant.now().minusSeconds(60), "PENDING");
        String delivered = transfer("SENT", Instant.now().minusSeconds(60), "DELIVERED");

        assertThat(CsmInvariants.gatewayNothingStuck(gateway, List.of(delivered), TOLERANCE))
                .as("the stuck transfer is not in this scope").isEmpty();
        assertViolation(CsmInvariants.gatewayNothingStuck(gateway, List.of(stuck, delivered), TOLERANCE), "INV-11",
                stuck);
    }

    private static void assertViolation(List<Violation> violations, String invariant, String subject) {
        assertThat(violations).as("%s fires for %s", invariant, subject).anySatisfy(violation -> {
            assertThat(violation.invariant()).isEqualTo(invariant);
            assertThat(violation.subject()).isEqualTo(subject);
        });
    }

    /** The answer {@code pacs.002} the CSM received for {@code payment}'s transaction. */
    private Answer answer(UUID payment, String msgId, String status, String reason) throws SQLException {
        return new Answer("PEERDEFFXXX", txIdOf(payment), msgId, status, reason);
    }

    /** The CSM's settlement of an incoming {@code payment}'s transaction from the peer bank. */
    private Settlement incomingSettlement(UUID payment, long amountMinor) throws SQLException {
        return new Settlement(txIdOf(payment), "PEERDEFFXXX", "INCOMING", amountMinor, Instant.now());
    }

    private static SimulatorMessage received(String txId, String messageType, Instant at) {
        return new SimulatorMessage(txId, "RECEIVED", messageType, at);
    }

    /** The CSM's settlement of {@code payment}'s transaction, as {@code GET /sim/v1/settlements} lists it. */
    private Settlement settlement(UUID payment, long amountMinor) throws SQLException {
        return new Settlement(txIdOf(payment), OUR_BIC, "OUTGOING", amountMinor, Instant.now());
    }

    // ---- databases

    private static void migrate(String database, String service, Map<String, String> placeholders) {
        Flyway.configure()
                .dataSource(url(database), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + Path.of("../../services/" + service + "/src/main/resources/db/migration")
                        .toAbsolutePath().normalize())
                .placeholders(placeholders)
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

    /** An outgoing transfer the gateway recorded; {@code transmitStatus} {@code null} for one never sent. */
    private String transfer(String sendStatus, Instant sendBy, String transmitStatus) throws SQLException {
        String txId = "TX" + NUMBERS.incrementAndGet();
        try (var statement = gateway.prepareStatement("""
                INSERT INTO outgoing_transfers (tx_id, payment_id, uetr, msg_id, accepted_at, send_by, deadline_at,
                  send_status, not_sent_reason, journalled_at, transmit_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?)""")) {
            statement.setString(1, txId);
            statement.setObject(2, UUID.randomUUID());
            statement.setObject(3, UUID.randomUUID());
            statement.setString(4, "MSG" + txId);
            statement.setTimestamp(5, Timestamp.from(sendBy.minusSeconds(10)));
            statement.setTimestamp(6, Timestamp.from(sendBy));
            statement.setTimestamp(7, Timestamp.from(sendBy.plusSeconds(10)));
            statement.setString(8, sendStatus);
            statement.setString(9, sendStatus.equals("NOT_SENT") ? "AB03" : null);
            statement.setString(10, transmitStatus);
            statement.executeUpdate();
        }
        return txId;
    }

    private UUID payment(String state, boolean onUs, long amountMinor) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var statement = payments.prepareStatement("""
                INSERT INTO payments (payment_id, direction, state, on_us, tx_id, uetr, end_to_end_id, amount_minor,
                  currency, debtor_name, debtor_iban, debtor_agent_bic, creditor_name, creditor_iban,
                  creditor_agent_bic, accepted_at, finalised_at, created_at, updated_at)
                VALUES (?, 'OUTGOING', ?, ?, ?, ?, 'E2E', ?, 'EUR', 'Payer', 'DE00PAYER', ?, 'Payee', 'DE00PAYEE',
                  'PEERDEFFXXX', now(), now(), now(), now())""")) {
            statement.setObject(1, id);
            statement.setString(2, state);
            statement.setBoolean(3, onUs);
            statement.setString(4, "TX" + NUMBERS.incrementAndGet());
            statement.setObject(5, UUID.randomUUID());
            statement.setLong(6, amountMinor);
            statement.setString(7, OUR_BIC);
            statement.executeUpdate();
        }
        return id;
    }

    /** An incoming payment from the peer bank to us. */
    private UUID incomingPayment(String state, String reasonCode, String csmOutcome, long amountMinor)
            throws SQLException {
        UUID id = UUID.randomUUID();
        try (var statement = payments.prepareStatement("""
                INSERT INTO payments (payment_id, direction, state, reason_code, on_us, tx_id, uetr, end_to_end_id,
                  msg_id, amount_minor, currency, debtor_name, debtor_iban, debtor_agent_bic, creditor_name,
                  creditor_iban, creditor_agent_bic, accepted_at, answer_deadline, csm_outcome, finalised_at,
                  created_at, updated_at)
                VALUES (?, 'INCOMING', ?, ?, false, ?, ?, 'E2E', 'MSG', ?, 'EUR', 'Payer', 'DE00PAYER', 'PEERDEFFXXX',
                  'Payee', 'DE00PAYEE', ?, now(), now(), ?, now(), now(), now())""")) {
            statement.setObject(1, id);
            statement.setString(2, state);
            statement.setString(3, reasonCode);
            statement.setString(4, "TX" + NUMBERS.incrementAndGet());
            statement.setObject(5, UUID.randomUUID());
            statement.setLong(6, amountMinor);
            statement.setString(7, OUR_BIC);
            statement.setString(8, csmOutcome);
            statement.executeUpdate();
        }
        return id;
    }

    /** An incoming transfer the gateway recorded, as {@code <debtorAgentBic>:<txId>}; its answer may be missing. */
    private String incomingTransfer(Instant answerDeadline, String answerStatus, String transmitStatus)
            throws SQLException {
        String txId = "TX" + NUMBERS.incrementAndGet();
        try (var statement = gateway.prepareStatement("""
                INSERT INTO incoming_transfers (debtor_agent_bic, tx_id, msg_id, received_at, answer_deadline,
                  answer_status, answer_reason, answer_decided_by, answered_at, transmit_status)
                VALUES ('PEERDEFFXXX', ?, ?, ?, ?, ?, NULL, CASE WHEN ? THEN 'VERDICT' END, CASE WHEN ? THEN now() END,
                  ?)""")) {
            statement.setString(1, txId);
            statement.setString(2, "MSG" + txId);
            statement.setTimestamp(3, Timestamp.from(answerDeadline.minusSeconds(2)));
            statement.setTimestamp(4, Timestamp.from(answerDeadline));
            statement.setString(5, answerStatus);
            statement.setBoolean(6, answerStatus != null);
            statement.setBoolean(7, answerStatus != null);
            statement.setString(8, transmitStatus);
            statement.executeUpdate();
        }
        return "PEERDEFFXXX:" + txId;
    }

    /** The incoming payment's {@code PAYMENT_IN} entry: DR {@code CSM_SETTLEMENT}, CR a customer. */
    private void incomingEntry(UUID payment, long amountMinor) throws SQLException {
        entry(payment, amountMinor, "PAYMENT_IN", "CR", "DR");
    }

    private String txIdOf(UUID payment) throws SQLException {
        try (var statement = payments.prepareStatement("SELECT tx_id FROM payments WHERE payment_id = ?")) {
            statement.setObject(1, payment);
            try (var rs = statement.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    /** The payment's {@code PAYMENT_OUT} entry: DR a customer, CR {@code CSM_SETTLEMENT}, of {@code amountMinor}. */
    private void entry(UUID payment, long amountMinor) throws SQLException {
        entry(payment, amountMinor, "PAYMENT_OUT", "DR", "CR");
    }

    /** An entry of {@code type}: the customer on {@code customerSide}, {@code CSM_SETTLEMENT} on {@code csmSide}. */
    private void entry(UUID payment, long amountMinor, String type, String customerSide, String csmSide)
            throws SQLException {
        UUID customer = UUID.randomUUID();
        UUID csm;
        try (var statement = ledger.prepareStatement(
                "SELECT account_id FROM accounts WHERE technical_code = 'CSM_SETTLEMENT'");
                var rs = statement.executeQuery()) {
            rs.next();
            csm = rs.getObject(1, UUID.class);
        }
        ledger.setAutoCommit(false);
        try {
            try (var statement = ledger.prepareStatement("""
                    INSERT INTO accounts (account_id, iban, kind, customer_id, status, booked_minor, held_minor,
                      opened_at)
                    VALUES (?, ?, 'CUSTOMER', ?, 'OPEN', 0, 0, now())""")) {
                statement.setObject(1, customer);
                statement.setString(2, "DE00" + "10010010" + NUMBERS.incrementAndGet());
                statement.setString(3, "CUST-" + customer);
                statement.executeUpdate();
            }
            UUID entry = UUID.randomUUID();
            try (var statement = ledger.prepareStatement("""
                    INSERT INTO journal_entries (entry_id, entry_type, payment_id, created_at)
                    VALUES (?, ?, ?, now())""")) {
                statement.setObject(1, entry);
                statement.setString(2, type);
                statement.setObject(3, payment);
                statement.executeUpdate();
            }
            for (var posting : List.of(Map.entry(customer, customerSide), Map.entry(csm, csmSide))) {
                try (var statement = ledger.prepareStatement("""
                        INSERT INTO postings (entry_id, account_id, side, amount_minor, created_at)
                        VALUES (?, ?, ?, ?, now())""")) {
                    statement.setObject(1, entry);
                    statement.setObject(2, posting.getKey());
                    statement.setString(3, posting.getValue());
                    statement.setLong(4, amountMinor);
                    statement.executeUpdate();
                }
            }
            ledger.commit();
        } finally {
            ledger.setAutoCommit(true);
        }
    }
}
