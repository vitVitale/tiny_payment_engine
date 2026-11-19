package com.tpe.systemtests.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.invariantchecker.CsmInvariants;
import com.tpe.invariantchecker.EventInvariants;
import com.tpe.invariantchecker.PaymentInvariants;
import com.tpe.invariantchecker.Violation;
import com.tpe.paymentdomain.CommandId;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import tools.jackson.databind.JsonNode;

/**
 * A system test of payments on the compose stack: payment-api, payment-orchestrator, risk-service and vop-service beside
 * ledger-service, and csm-gateway with csm-simulator as the CSM and the peer banks behind it, each told its neighbours'
 * ports; the vop and risk projections fed by the ledger's real {@code accounts.events}, vop-service pointed at the
 * simulator's responder for a peer bank's payee (asked once its remote client exists); a {@link TopicRecorder} on
 * {@code payments.events},
 * {@code csm.outbound.commands} and the dead-letter topics. After every test, beside the ledger's checks, the per-test
 * invariants over the payments the test initiated or received (specs/20-testing-strategy.md §3): INV-3, INV-4, INV-5,
 * INV-6, INV-7, INV-8, INV-9, INV-10, INV-11 (orchestrator and gateway parts, outgoing and incoming). INV-13 (webhooks)
 * comes with the notifications track.
 *
 * <p>As for {@link LedgerSystemTest}, every subclass carries
 * {@code @EnabledIfSystemProperty(named = "tpe.local-stack", matches = "true")} itself.
 */
public abstract class PaymentSystemTest extends LedgerSystemTest {

    /** A back-office operator of the ledger: opens, funds, blocks and closes the tests' accounts. */
    protected static final String OPERATOR = MockIssuer.staff("systest-operator", "BACKOFFICE_OPERATOR");

    private static final TopicRecorder RECORDER;
    private static final JsonHttp PAYMENT_API;
    private static final JsonHttp LEDGER;
    private static final URI VOP;
    private static final URI ORCHESTRATOR;
    private static final ServiceProcess CSM_GATEWAY;
    /** csm-gateway's admin API (15 §3.2), for the debug bundle. */
    private static final JsonHttp GATEWAY_ADMIN;
    private static final JsonHttp CASE_API;
    private static final SimulatorControl SIMULATOR;
    /** The second payment-orchestrator of the chaos run ({@link Chaos}); {@code null} otherwise. */
    private static final ServiceProcess ORCHESTRATOR_B;
    /** The second csm-gateway of the chaos run, the one CH-02 kills; {@code null} otherwise. */
    private static final ServiceProcess CSM_GATEWAY_B;
    /** What the second csm-gateway of the chaos run calls the CSM through; {@code null} otherwise. */
    private static final HostProxy CSM_LINK_B;
    /** Every settlement the CSM made under this JVM's tests is at or after this. */
    private static final Instant STARTED = Instant.now();

    static {
        var topics = new ArrayList<>(List.of(TopicRecorder.PAYMENTS_EVENTS, TopicRecorder.CSM_OUTBOUND_COMMANDS));
        topics.addAll(TopicRecorder.DEAD_LETTER_TOPICS);
        RECORDER = TopicRecorder.start("localhost:9092", topics);
        URI ledger = ServiceProcess.of(ServiceJar.LEDGER).baseUrl();
        URI risk = ServiceProcess.of(ServiceJar.RISK).baseUrl();
        // the gateway and the simulator call each other: the gateway's port is known before either starts
        int gatewayPort = ServiceProcess.freePort();
        URI simulator = ServiceProcess.of(ServiceJar.CSM_SIMULATOR, Map.of(
                "tpe.sim.gateway-url", "http://localhost:" + gatewayPort)).baseUrl();
        SIMULATOR = new SimulatorControl(simulator);
        // once at suite start, never during a run (20 §2)
        SIMULATOR.reset();
        // a test stops it (E2E-O-21): on Windows by terminating it, so its consumer never leaves the group and the
        // restarted one is assigned nothing until the old one's session times out; the broker's shortest session
        // bounds that, a 1 s heartbeat keeps a frozen one (E2E-O-12, about 4 s) in its group
        var gateway = Map.of(
                "spring.kafka.consumer.properties.session.timeout.ms", "6000",
                "spring.kafka.consumer.heartbeat-interval", "1s");
        CSM_GATEWAY = ServiceProcess.of(ServiceJar.CSM_GATEWAY, with(gateway,
                "tpe.csm.base-url", simulator.toString()), gatewayPort);
        GATEWAY_ADMIN = new JsonHttp(CSM_GATEWAY.baseUrl());
        VOP = ServiceProcess.of(ServiceJar.VOP, Map.of(
                "tpe.bank-directory.location", BankDirectoryFile.withSimulator(simulator))).baseUrl();
        LEDGER = new JsonHttp(ledger);
        var orchestrator = new HashMap<>(Map.of(
                "tpe.clients.risk.base-url", risk.toString(),
                "tpe.clients.ledger.base-url", ledger.toString()));
        if (Chaos.enabled()) {
            // CH-03: every instance produces through the proxy, and consumes directly
            KafkaProduceProxy.get();
            orchestrator.put("spring.kafka.bootstrap-servers", KafkaProduceProxy.BOOTSTRAP);
            orchestrator.put("spring.kafka.consumer.bootstrap-servers", "localhost:9092");
        }
        ORCHESTRATOR = ServiceProcess.of(ServiceJar.ORCHESTRATOR, orchestrator).baseUrl();
        if (Chaos.enabled()) {
            // the second instance of each (20 §5); the simulator and payment-api call the first. CH-02 kills the
            // second gateway, which reaches the CSM through a proxy that can slow its sends down
            ORCHESTRATOR_B = ServiceProcess.of(ServiceJar.ORCHESTRATOR.name() + "-b", ServiceJar.ORCHESTRATOR,
                    orchestrator);
            CSM_LINK_B = HostProxy.of("csm-gateway-b-to-csm", simulator);
            CSM_GATEWAY_B = ServiceProcess.of(ServiceJar.CSM_GATEWAY.name() + "-b", ServiceJar.CSM_GATEWAY,
                    with(gateway, "tpe.csm.base-url", CSM_LINK_B.uri().toString()));
        } else {
            ORCHESTRATOR_B = null;
            CSM_LINK_B = null;
            CSM_GATEWAY_B = null;
        }
        CASE_API = new JsonHttp(ORCHESTRATOR);
        PAYMENT_API = new JsonHttp(ServiceProcess.of(ServiceJar.PAYMENT_API, Map.of(
                "tpe.clients.orchestrator.base-url", ORCHESTRATOR.toString(),
                "tpe.clients.vop.base-url", VOP.toString())).baseUrl());
    }

    private static Map<String, String> with(Map<String, String> properties, String key, String value) {
        var all = new HashMap<>(properties);
        all.put(key, value);
        return all;
    }

    /** The case types this test opens on purpose; JUnit makes a new instance per test. */
    private final Set<String> expectedCases = new CopyOnWriteArraySet<>();

    /** The payments this test initiated or received: the scope of its invariant checks. */
    private final CopyOnWriteArrayList<UUID> payments = new CopyOnWriteArrayList<>();

    /**
     * After the test, at quiescence (20 §3): INV-3, -4, -5, -6, -7, -8, -9, -10, -11. Quiescence comes by the
     * payments' deadlines: a
     * final event is delivered after the final state, and the deadline timer of an on-us payment stays
     * {@code SCHEDULED} until it fires as a no-op (11 §6.4). So the checks are repeated until they hold, at most
     * {@link Bounds#paymentFinal()}, and what is left then is the failure: a passing test waits for its own timers only.
     * "Exactly one" final event (INV-7) holds only once a second one would have arrived: a payment's checks hold no
     * earlier than {@link Bounds#eventDelivery()} after its first final event was read.
     */
    @AfterEach
    void paymentInvariantsHoldForTheTestsPayments() throws SQLException {
        RECORDER.assertHealthy();
        try {
            await().atMost(Bounds.paymentFinal()).pollInterval(Duration.ofMillis(250))
                    .until(() -> paymentViolations().isEmpty() && finalEventsSettled());
        } catch (ConditionTimeoutException e) {
            // reported below, as found now
        }
        assertThat(paymentViolations()).as("INV-3, -4, -5, -6, -7, -8, -9, -10, -11 over %s", payments).isEmpty();
    }

    /** Every final event of the test's payments was read a whole delivery bound ago: a duplicate would be here. */
    private boolean finalEventsSettled() {
        Instant settled = Instant.now().minus(Bounds.eventDelivery());
        return payments.stream().map(RECORDER::firstFinalEventReadAt)
                .allMatch(first -> first.map(read -> read.isBefore(settled)).orElse(true));
    }

    private List<Violation> paymentViolations() throws SQLException {
        try (var orchestrator = LocalDatabases.payments(); var ledger = LocalDatabases.ledger();
                var gateway = LocalDatabases.csm()) {
            Map<UUID, String> states = PaymentInvariants.states(orchestrator, payments);
            List<Violation> violations = new ArrayList<>();
            List<String> transfers = transfersToPeers(orchestrator);
            violations.addAll(CsmInvariants.noTransmissionAfterSendBy(gateway,
                    transfers.stream().flatMap(txId -> SIMULATOR.received(txId).stream()).toList(),
                    Bounds.transmissionInFlight()));
            violations.addAll(CsmInvariants.ledgerAgreesWithCsm(orchestrator, ledger, SIMULATOR.settlements(STARTED),
                    payments));
            violations.addAll(CsmInvariants.gatewayNothingStuck(gateway, transfers, Bounds.transmissionOverdue()));
            List<String> incoming = incomingTransactions(orchestrator);
            violations.addAll(CsmInvariants.oneAnswerPerIncoming(orchestrator, incoming.stream()
                    .map(transaction -> transaction.substring(transaction.indexOf(':') + 1)).distinct()
                    .flatMap(txId -> SIMULATOR.answers(txId).stream()).toList(), payments));
            violations.addAll(CsmInvariants.gatewayIncomingNothingStuck(gateway, incoming, Bounds.answerOverdue()));
            violations.addAll(PaymentInvariants.holdsMatchOutcomes(orchestrator, ledger, payments));
            violations.addAll(PaymentInvariants.pendingCreditsMatchOutcomes(orchestrator, ledger, payments));
            violations.addAll(PaymentInvariants.csmSettlementPostings(orchestrator, ledger, payments));
            violations.addAll(PaymentInvariants.noUnexpectedCases(orchestrator, payments, expectedCases()));
            violations.addAll(PaymentInvariants.nothingStuck(orchestrator, payments, Bounds.actionOverdue()));
            violations.addAll(EventInvariants.finalEvents(states, RECORDER.finalEvents(payments)));
            violations.addAll(EventInvariants.noDeadLetters(RECORDER.deadLetters()));
            return violations;
        }
    }

    /** The {@code txId} of every payment of the test to another PSP: what the CSM side may know of it. */
    private List<String> transfersToPeers(Connection orchestrator) throws SQLException {
        try (var statement = orchestrator.prepareStatement("""
                SELECT tx_id FROM payments
                 WHERE payment_id = ANY (?) AND direction = 'OUTGOING' AND NOT on_us ORDER BY created_at""")) {
            statement.setArray(1, orchestrator.createArrayOf("uuid", payments.toArray(UUID[]::new)));
            var txIds = new ArrayList<String>();
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    txIds.add(rs.getString(1));
                }
            }
            return txIds;
        }
    }

    /** {@code <debtorAgentBic>:<txId>} of every incoming payment of the test: what the gateway and the CSM know. */
    private List<String> incomingTransactions(Connection orchestrator) throws SQLException {
        try (var statement = orchestrator.prepareStatement("""
                SELECT debtor_agent_bic || ':' || tx_id FROM payments
                 WHERE payment_id = ANY (?) AND direction = 'INCOMING' ORDER BY created_at""")) {
            statement.setArray(1, orchestrator.createArrayOf("uuid", payments.toArray(UUID[]::new)));
            var transactions = new ArrayList<String>();
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    transactions.add(rs.getString(1));
                }
            }
            return transactions;
        }
    }

    /** An incoming transfer a test received: its transaction (ADR-010) and the orchestrator's payment of it. */
    public record IncomingTransfer(String txId, String debtorAgentBic, UUID payment) {}

    /**
     * The CSM delivers {@code amount} EUR (a decimal string) from a fresh debtor at the peer bank to
     * {@code creditorIban}, played as {@code scenario} (17 §3.2); once csm-gateway took it and the orchestrator made
     * its payment, the payment joins the checks.
     */
    protected IncomingTransfer received(String creditorIban, String amount, String scenario) {
        TestData.Customer debtor = TestData.newCustomer();
        String txId = SIMULATOR.incomingPayment(creditorIban, amount, debtor.name(), TestData.peerIban(), scenario);
        var found = new AtomicReference<IncomingTransfer>();
        try {
            await().atMost(Bounds.csmEventHandled()).pollInterval(Duration.ofMillis(100)).until(() -> {
                found.set(incomingPayment(txId));
                return found.get() != null;
            });
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("no incoming payment of txId " + txId + " within " + Bounds.csmEventHandled(), e);
        }
        payments.addIfAbsent(found.get().payment());
        return found.get();
    }

    /** The orchestrator's incoming payment of {@code txId}, from whichever debtor agent; {@code null} if none yet. */
    private static IncomingTransfer incomingPayment(String txId) throws SQLException {
        try (var orchestrator = LocalDatabases.payments(); var statement = orchestrator.prepareStatement("""
                SELECT payment_id, debtor_agent_bic FROM payments WHERE direction = 'INCOMING' AND tx_id = ?""")) {
            statement.setString(1, txId);
            try (var rs = statement.executeQuery()) {
                return rs.next() ? new IncomingTransfer(txId, rs.getString(2), rs.getObject(1, UUID.class)) : null;
            }
        }
    }

    /** The orchestrator's state of {@code payment} once final, within {@code bound}: an incoming one has no API. */
    protected static String finalState(UUID payment, Duration bound) {
        var state = new AtomicReference<String>();
        await().atMost(bound).pollInterval(Duration.ofMillis(100)).until(() -> {
            state.set(orchestratorPayment(payment).state());
            return state.get().equals("SETTLED") || state.get().equals("REJECTED");
        });
        return state.get();
    }

    /** The ledger's pending credit of an incoming {@code payment} ({@code GET /v1/pending-credits/{id}}). */
    protected static Response pendingCredit(UUID payment) {
        return LEDGER.get(null, "/v1/pending-credits/" + payment);
    }

    /** The exception case types this test opens on purpose ({@link #expectCase}); any other is a breach of INV-9. */
    protected Set<String> expectedCases() {
        return Set.copyOf(expectedCases);
    }

    /** This test opens a case of {@code type} on purpose, e.g. E2E-O-11 its {@code UNRESOLVED_INVESTIGATION}. */
    protected void expectCase(String type) {
        expectedCases.add(type);
    }

    /** The orchestrator's exception case API (11 §3.2), as a back-office operator calls it with {@link #OPERATOR}. */
    protected static JsonHttp caseApi() {
        return CASE_API;
    }

    /** {@code GET /v1/payments/{id}} as {@code payer}, now. */
    protected JsonNode status(Party payer, UUID payment) {
        return PAYMENT_API.get(payer.token(), "/v1/payments/" + payment).body();
    }

    /** payment-api, for the customer channel's calls (POST with a fresh {@code Idempotency-Key}). */
    protected static JsonHttp paymentApi() {
        return PAYMENT_API;
    }

    /** payment-orchestrator, for its metrics and for a proxy in front of it. */
    protected static URI orchestratorService() {
        return ORCHESTRATOR;
    }

    /** csm-simulator's control API: the peer banks' accounts, the CSM's scenarios and logs. */
    protected static SimulatorControl simulator() {
        return SIMULATOR;
    }

    /** csm-gateway, which a test may stop and start again (its port and properties stay). */
    protected static ServiceProcess csmGateway() {
        return CSM_GATEWAY;
    }

    /** The chaos run's payment-orchestrators, first the one payment-api calls. */
    protected static List<URI> orchestratorServices() {
        requireChaos();
        return List.of(ORCHESTRATOR, ORCHESTRATOR_B.baseUrl());
    }

    /** The chaos run's second csm-gateway: killed and started again by CH-02, never the one the CSM calls. */
    protected static ServiceProcess secondCsmGateway() {
        requireChaos();
        return CSM_GATEWAY_B;
    }

    /** The link of the chaos run's second csm-gateway to the CSM. */
    protected static HostProxy secondCsmGatewayLink() {
        requireChaos();
        return CSM_LINK_B;
    }

    private static void requireChaos() {
        if (!Chaos.enabled()) {
            throw new IllegalStateException("only in the chaos run (-Pchaos, -Dtpe.chaos=true)");
        }
    }

    /**
     * A payee at the peer bank ({@code PEERDEFFXXX}, bank code {@code 20020020}): a fresh IBAN and name, an account of
     * the simulator's VoP responder, and {@code scenario} for the CSM's answer to a transfer to it.
     */
    protected static Payee peerPayee(String scenario) {
        TestData.Customer holder = TestData.newCustomer();
        String iban = TestData.peerIban();
        SIMULATOR.counterpartyAccount(iban, holder.name());
        SIMULATOR.scenario(iban, scenario);
        return new Payee(holder.name(), iban);
    }

    /** A payee at another PSP: holder name and IBAN. */
    public record Payee(String name, String iban) {}

    /** What {@link #paidToPeer} pays: 123.45 EUR. */
    protected static final long PEER_PAYMENT_MINOR = 12_345;

    /**
     * The payee verified, then {@code payer} pays it 123.45 EUR under {@code endToEndId}; the payment's id. vop-service
     * cannot ask a peer bank's responder yet (its {@code NoCounterpartyVop} until the VoP remote track, 13 §6.1 step
     * 3), so the reference says {@code NOT_POSSIBLE}, which the customer may pay on (UC-02 3a) and risk lets pass at
     * this amount; with the remote client the simulator's responder answers {@code MATCH} for the account
     * {@link #peerPayee} seeded.
     */
    protected UUID paidToPeer(Party payer, Payee payee, String endToEndId) {
        return paidToPeer(payer, payee, endToEndId, verifiedPeer(payer, payee));
    }

    /** The VoP reference {@link #paidToPeer} pays on: taken before an outage, which then starts at the submit. */
    protected String verifiedPeer(Party payer, Payee payee) {
        return verified(payer, payee.iban(), payee.name(), "NOT_POSSIBLE").get("vopReference").asString();
    }

    /** As {@link #paidToPeer(Party, Payee, String)} on a reference {@link #verifiedPeer} took before. */
    protected UUID paidToPeer(Party payer, Payee payee, String endToEndId, String vopReference) {
        Response accepted = initiate(payer.token(), Map.of(
                "endToEndId", endToEndId,
                "debtor", Map.of("name", payer.name(), "iban", payer.iban()),
                "creditor", Map.of("name", payee.name(), "iban", payee.iban()),
                "amount", Map.of("value", BigDecimal.valueOf(PEER_PAYMENT_MINOR, 2).toPlainString(),
                        "currency", "EUR"),
                "remittanceInformation", endToEndId + " to another PSP",
                "vopReference", vopReference));
        assertThat(accepted.status()).as("%s", accepted.body()).isEqualTo(202);
        return UUID.fromString(accepted.body().get("paymentId").asString());
    }

    /** The payment's row in the orchestrator, as far as the CSM side needs it. */
    public record OrchestratorPayment(String txId, Instant acceptedAt, String state, Instant sendBy,
            Instant deadlineAt, String reasonCode) {}

    /** The orchestrator's {@code txId}, acceptance, state, {@code sendBy}, deadline and reason of a payment, now. */
    protected static OrchestratorPayment orchestratorPayment(UUID payment) throws SQLException {
        try (var orchestrator = LocalDatabases.payments(); var statement = orchestrator.prepareStatement(
                """
                SELECT tx_id, accepted_at, state, send_by, deadline_at, reason_code FROM payments
                 WHERE payment_id = ?""")) {
            statement.setObject(1, payment);
            try (var rs = statement.executeQuery()) {
                rs.next();
                return new OrchestratorPayment(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getString(3),
                        instant(rs.getTimestamp(4)), instant(rs.getTimestamp(5)), rs.getString(6));
            }
        }
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** The messages of {@code messageType} (e.g. {@code pacs.008.001.08}) the CSM received for the payment. */
    protected static List<CsmInvariants.SimulatorMessage> csmReceived(UUID payment, String messageType)
            throws SQLException {
        return SIMULATOR.received(orchestratorPayment(payment).txId()).stream()
                .filter(message -> message.kind().equals("RECEIVED") && message.messageType().equals(messageType))
                .toList();
    }

    /** The payment's {@code OPEN} exception case of {@code type}; {@code null} if none. */
    protected static UUID openCase(UUID payment, String type) throws SQLException {
        try (var orchestrator = LocalDatabases.payments(); var statement = orchestrator.prepareStatement(
                "SELECT case_id FROM exception_cases WHERE payment_id = ? AND type = ? AND status = 'OPEN'")) {
            statement.setObject(1, payment);
            statement.setString(2, type);
            try (var rs = statement.executeQuery()) {
                return rs.next() ? rs.getObject(1, UUID.class) : null;
            }
        }
    }

    /** The notes csm-gateway's journal has for the transaction, e.g. {@code duplicate status}. */
    protected static List<String> gatewayNotes(String txId) throws SQLException {
        try (var gateway = LocalDatabases.csm(); var statement = gateway.prepareStatement(
                "SELECT note FROM message_journal WHERE tx_id = ? AND note IS NOT NULL ORDER BY id")) {
            statement.setString(1, txId);
            var notes = new ArrayList<String>();
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    notes.add(rs.getString(1));
                }
            }
            return notes;
        }
    }

    /**
     * The CSM's settlements of {@code payment}'s transaction (17 §3.5). Read from a second before its acceptance: no
     * settlement of it is earlier, whatever the small skew between the database's clock and the simulator's.
     */
    protected static List<CsmInvariants.Settlement> csmSettlements(UUID payment) throws SQLException {
        OrchestratorPayment found = orchestratorPayment(payment);
        return SIMULATOR.settlements(found.acceptedAt().minusSeconds(1)).stream()
                .filter(settlement -> settlement.txId().equals(found.txId())).toList();
    }

    /** The orchestrator's transitions of {@code payment}, as {@code from|to|cause}, oldest first. */
    protected static List<String> transitions(UUID payment) throws SQLException {
        try (var orchestrator = LocalDatabases.payments(); var statement = orchestrator.prepareStatement("""
                SELECT coalesce(from_state, '-') || '|' || to_state || '|' || cause FROM payment_transitions
                 WHERE payment_id = ? ORDER BY id""")) {
            statement.setObject(1, payment);
            var found = new ArrayList<String>();
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    found.add(rs.getString(1));
                }
            }
            return found;
        }
    }

    /** vop-service, for its metrics. */
    protected static URI vopService() {
        return VOP;
    }

    /** The recorder of the payment topics. */
    protected static TopicRecorder topics() {
        return RECORDER;
    }

    /**
     * A customer of ours: an account opened and funded with {@code fundedMinor} (none when 0), and a customer-channel
     * token that owns it with every customer scope.
     */
    protected Party customer(long fundedMinor) {
        TestData.Customer customer = TestData.newCustomer();
        Response opened = open(OPERATOR, customer);
        assertThat(opened.status()).as("open: %s", opened.body()).isEqualTo(201);
        UUID account = UUID.fromString(opened.body().get("accountId").asString());
        String iban = opened.body().get("iban").asString();
        if (fundedMinor > 0) {
            Response funded = ledger().fund(OPERATOR, account, fundedMinor);
            assertThat(funded.status()).as("fund: %s", funded.body()).isEqualTo(200);
        }
        return new Party(customer.name(), account, iban, MockIssuer.customer("systest-" + account, iban,
                CustomerScope.VOP_VERIFY, CustomerScope.PAYMENTS_WRITE, CustomerScope.PAYMENTS_READ));
    }

    /** A customer as a test pays from or to it: holder name, ledger account, IBAN, and its channel token. */
    public record Party(String name, UUID account, String iban, String token) {}

    /**
     * {@code POST /v1/payee-verifications} by {@code payer} until vop-service answers {@code result}: its projection of
     * {@code accounts.events} follows the ledger within {@link Bounds#eventDelivery()}. Every call issues a new
     * reference; the last one's response is returned.
     */
    protected JsonNode verified(Party payer, String payeeIban, String payeeName, String result) {
        var last = new AtomicReference<Response>();
        try {
            await().atMost(Bounds.eventDelivery()).pollInterval(Duration.ofMillis(250)).until(() -> {
                last.set(PAYMENT_API.post(payer.token(), "/v1/payee-verifications", Map.of(
                        "debtorIban", payer.iban(), "payeeIban", payeeIban, "payeeName", payeeName)));
                return last.get().status() == 201 && result.equals(last.get().body().get("result").asString());
            });
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("payee verification never answered " + result + "; last: " + last.get(), e);
        }
        return last.get().body();
    }

    /** {@code payer} pays {@code amount} EUR (a decimal string) to {@code payeeIban} with the VoP reference. */
    protected Response pay(Party payer, String payeeIban, String payeeName, String amount, String vopReference) {
        return initiate(payer.token(), payment(payer, payeeIban, payeeName, amount, vopReference));
    }

    /** The JSON body of {@code POST /v1/payments} (10 §3.1) for {@link #pay}. */
    protected static Map<String, Object> payment(Party payer, String payeeIban, String payeeName, String amount,
            String vopReference) {
        var body = new HashMap<String, Object>(Map.of(
                "endToEndId", "SYSTEST-" + Long.toHexString(System.nanoTime()),
                "debtor", Map.of("name", payer.name(), "iban", payer.iban()),
                "creditor", Map.of("name", payeeName, "iban", payeeIban),
                "amount", Map.of("value", amount, "currency", "EUR")));
        if (vopReference != null) {
            body.put("vopReference", vopReference);
        }
        return body;
    }

    /** As {@link #pay}, asserted {@code 202}; the payment's id. */
    protected UUID initiated(Party payer, String payeeIban, String payeeName, String amount, String vopReference) {
        Response accepted = pay(payer, payeeIban, payeeName, amount, vopReference);
        assertThat(accepted.status()).as("%s", accepted.body()).isEqualTo(202);
        return UUID.fromString(accepted.body().get("paymentId").asString());
    }

    /** {@code GET /v1/payments/{id}} as {@code payer} once it is no longer {@code PDNG}: what the customer sees. */
    protected JsonNode finalStatus(Party payer, UUID payment) {
        return finalStatus(payer, payment, Bounds.paymentFinal());
    }

    /** As {@link #finalStatus(Party, UUID)} within {@code bound}, e.g. after an investigation. */
    protected JsonNode finalStatus(Party payer, UUID payment, Duration bound) {
        var status = new AtomicReference<JsonNode>();
        await().atMost(bound).pollInterval(Duration.ofMillis(100)).until(() -> {
            status.set(PAYMENT_API.get(payer.token(), "/v1/payments/" + payment).body());
            return status.get() != null && !"PDNG".equals(status.get().get("status").asString());
        });
        return status.get();
    }

    /** The ledger's hold of {@code payment} ({@code GET /v1/holds/{holdId}}, the hold id being the payment's). */
    protected static Response hold(UUID payment) {
        return LEDGER.get(null, "/v1/holds/" + payment);
    }

    /**
     * {@code tpe.payment.rejected{direction=OUTGOING,reason}} of the orchestrator now, per {@code cause}: taken before a
     * payment, then {@link RejectedCount#assertTaggedOnce assert}ed after it. The counter is shared, so the check is that
     * the expected cause moved and the other one did not: a rejection of this payment under the wrong tag fails it,
     * whatever another test class rejects meanwhile under the right one.
     */
    protected static RejectedCount rejectedOutgoing(String reason) {
        return rejectedCount("OUTGOING", reason);
    }

    /** As {@link #rejectedOutgoing}, for an incoming payment: {@code tpe.payment.rejected{direction=INCOMING}}. */
    protected static RejectedCount rejectedIncoming(String reason) {
        return rejectedCount("INCOMING", reason);
    }

    private static RejectedCount rejectedCount(String direction, String reason) {
        var counts = new HashMap<String, Double>();
        for (String cause : CAUSES) {
            counts.put(cause, rejected(direction, reason, cause));
        }
        return new RejectedCount(direction, reason, Map.copyOf(counts));
    }

    /** The {@code cause} tags of {@code tpe.payment.rejected} (25 §11): a business refusal, ours, the scheme's. */
    private static final List<String> CAUSES = List.of("BUSINESS", "TECHNICAL", "EXTERNAL");

    /** The counts of a reason under each {@code cause} at one moment. */
    public record RejectedCount(String direction, String reason, Map<String, Double> byCause) {

        /** The reason was counted under {@code cause} since this snapshot, and under no other cause. */
        public void assertTaggedOnce(String cause) {
            RejectedCount now = rejectedCount(direction, reason);
            for (String tag : CAUSES) {
                if (tag.equals(cause)) {
                    assertThat(now.byCause().get(tag)).as("tpe.payment.rejected{reason=%s,cause=%s}", reason, tag)
                            .isGreaterThan(byCause().get(tag));
                } else {
                    assertThat(now.byCause().get(tag))
                            .as("tpe.payment.rejected{reason=%s} under the other cause %s", reason, tag)
                            .isEqualTo(byCause().get(tag));
                }
            }
        }
    }

    private static double rejected(String direction, String reason, String cause) {
        return Scrape.value(ORCHESTRATOR, "tpe_payment_rejected_total",
                Map.of("direction", direction, "reason", reason, "cause", cause));
    }

    /**
     * The ledger's postings of {@code payment} on the CSM settlement account, as {@code <side> <amountMinor>}: INV-8's
     * side of a payment through the CSM.
     */
    protected static List<String> csmSettlementPostings(UUID payment) throws SQLException {
        try (var ledger = LocalDatabases.ledger(); var statement = ledger.prepareStatement("""
                SELECT p.side || ' ' || p.amount_minor FROM postings p
                  JOIN journal_entries e ON e.entry_id = p.entry_id
                  JOIN accounts a ON a.account_id = p.account_id
                 WHERE a.technical_code = 'CSM_SETTLEMENT' AND e.payment_id = ? ORDER BY p.posting_id""")) {
            statement.setObject(1, payment);
            var found = new ArrayList<String>();
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    found.add(rs.getString(1));
                }
            }
            return found;
        }
    }

    /** The account's postings of {@code payment}, as {@code <entryType> <side> <amountMinor>}. */
    protected List<String> postings(UUID account, UUID payment) {
        JsonNode page = ledger().entries(OPERATOR, account).body();
        return page.get("postings").valueStream()
                .filter(posting -> posting.hasNonNull("paymentId")
                        && payment.toString().equals(posting.get("paymentId").asString()))
                .map(posting -> posting.get("entryType").asString() + " " + posting.get("side").asString() + " "
                        + posting.get("amountMinor").asLong())
                .toList();
    }

    /** The ledger's available balance of {@code party}'s account, in minor units. */
    protected static long available(Party party) {
        return ledger().account(OPERATOR, party.account()).body().get("availableBalanceMinor").asLong();
    }

    /** The ledger's booked balance of {@code party}'s account, in minor units. */
    protected static long booked(Party party) {
        return ledger().account(OPERATOR, party.account()).body().get("bookedBalanceMinor").asLong();
    }

    /** {@code POST /v1/payments} as JSON; an accepted payment joins the checks. */
    protected Response initiate(String authorization, Map<String, ?> payment) {
        return initiate(PAYMENT_API, authorization, payment, newIdempotencyKey());
    }

    /**
     * {@code POST /v1/payments} to {@code api} (the harness's payment-api, or another instance of it) with
     * {@code idempotencyKey}; an accepted payment joins the checks.
     */
    protected Response initiate(JsonHttp api, String authorization, Map<String, ?> payment, String idempotencyKey) {
        Response response = api.post(authorization, "/v1/payments", payment, idempotencyKey);
        if (response.status() == 202) {
            UUID accepted = UUID.fromString(response.body().get("paymentId").asString());
            payments.addIfAbsent(accepted);
        }
        return response;
    }

    /**
     * The orchestrator's payments of the customer channel's {@code idempotencyKey}: those of its {@code commandId}
     * (10 §3.1 step 5), whatever payment-api answered.
     */
    protected static List<UUID> orchestratorPaymentsOf(String idempotencyKey) throws SQLException {
        UUID command = CommandId.derive(MockIssuer.CUSTOMER_CHANNEL, idempotencyKey).value();
        try (var orchestrator = LocalDatabases.payments();
                var statement = orchestrator.prepareStatement("SELECT payment_id FROM payments WHERE command_id = ?")) {
            statement.setObject(1, command);
            var found = new ArrayList<UUID>();
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    found.add(rs.getObject(1, UUID.class));
                }
            }
            return found;
        }
    }

    /** A key no other request used, valid for payment-api ({@code [A-Za-z0-9_-]{1,64}}). */
    protected static String newIdempotencyKey() {
        return "systest-" + UUID.randomUUID();
    }

    /** The payments this test initiated, in order. */
    protected List<UUID> payments() {
        return List.copyOf(payments);
    }

    /**
     * The ledger's part, then the test's payments, their transitions, cases, postings, holds, pending credits and
     * recorded records, and the CSM side of its transfers, outgoing and incoming: the gateway's records and journal (an
     * incoming one by {@code txId} and debtor agent), the simulator's received log.
     */
    @Override
    public String debugBundle() throws SQLException {
        var bundle = new StringBuilder(super.debugBundle());
        try (var orchestrator = LocalDatabases.payments(); var ledger = LocalDatabases.ledger();
                var gateway = LocalDatabases.csm()) {
            List<String> transfers = transfersToPeers(orchestrator);
            List<String> incoming = incomingTransactions(orchestrator);
            bundle.append(SqlDump.ofKeys(gateway, "gateway incoming transfers", """
                    SELECT debtor_agent_bic, tx_id, msg_id, received_at, answer_deadline, answer_status, answer_reason,
                           answer_decided_by, answered_at, answer_msg_id, transmit_status, transmit_attempts,
                           final_status, final_reason, final_at
                      FROM incoming_transfers WHERE debtor_agent_bic || ':' || tx_id = ANY (?) ORDER BY received_at""",
                    incoming));
            bundle.append("== gateway journal of incoming transfers (GET /v1/journal?txId=&debtorAgentBic=)\n");
            for (String transaction : incoming) {
                String bic = transaction.substring(0, transaction.indexOf(':'));
                String txId = transaction.substring(transaction.indexOf(':') + 1);
                bundle.append(transaction).append('\t').append(GATEWAY_ADMIN.get(OPERATOR,
                        "/v1/journal?txId=" + txId + "&debtorAgentBic=" + bic).body()).append('\n');
            }
            bundle.append("== simulator received log of incoming transfers (GET /sim/v1/received?txId=)\n");
            for (String txId : incoming.stream().map(t -> t.substring(t.indexOf(':') + 1)).distinct().toList()) {
                bundle.append(txId).append('\t').append(SIMULATOR.receivedLog(txId)).append('\n');
            }
            bundle.append(SqlDump.ofKeys(gateway, "gateway outgoing transfers", """
                    SELECT tx_id, payment_id, msg_id, send_by, deadline_at, send_status, not_sent_reason,
                           transmit_status, transmit_attempts, delivered_at, final_status, final_reason, final_source,
                           final_at
                      FROM outgoing_transfers WHERE tx_id = ANY (?) ORDER BY journalled_at""", transfers));
            bundle.append("== gateway journal (GET /v1/journal?txId=)\n");
            for (String txId : transfers) {
                bundle.append(txId).append('\t').append(GATEWAY_ADMIN.get(OPERATOR, "/v1/journal?txId=" + txId).body())
                        .append('\n');
            }
            bundle.append("== simulator received log (GET /sim/v1/received?txId=)\n");
            for (String txId : transfers) {
                bundle.append(txId).append('\t').append(SIMULATOR.receivedLog(txId)).append('\n');
            }
            bundle.append(SqlDump.of(orchestrator, "payments", """
                    SELECT payment_id, state, reason_code, on_us, tx_id, amount_minor, pending_action, next_action_at,
                           action_attempts, final_event_emitted, accepted_at, finalised_at
                      FROM payments WHERE payment_id = ANY (?) ORDER BY created_at""", payments));
            bundle.append(SqlDump.of(orchestrator, "payment transitions", """
                    SELECT payment_id, at, from_state, to_state, reason_code, cause, actor FROM payment_transitions
                     WHERE payment_id = ANY (?) ORDER BY id""", payments));
            bundle.append(SqlDump.of(orchestrator, "payment timers", """
                    SELECT payment_id, type, due_at, attempt, status FROM payment_timers
                     WHERE payment_id = ANY (?) ORDER BY timer_id""", payments));
            bundle.append(SqlDump.of(orchestrator, "exception cases", """
                    SELECT payment_id, case_id, type, status, opened_at, details FROM exception_cases
                     WHERE payment_id = ANY (?) ORDER BY opened_at""", payments));
            bundle.append(SqlDump.of(ledger, "ledger entries of the test's payments", """
                    SELECT e.created_at, e.entry_id, e.entry_type, e.payment_id, p.account_id, p.side, p.amount_minor
                      FROM journal_entries e JOIN postings p ON p.entry_id = e.entry_id
                     WHERE e.payment_id = ANY (?) ORDER BY e.created_at, p.posting_id""", payments));
            bundle.append(SqlDump.of(ledger, "ledger pending credits of the test's payments", """
                    SELECT pending_credit_id, account_id, amount_minor, status, entry_id, finalised_at, tombstone
                      FROM pending_credits WHERE pending_credit_id = ANY (?) ORDER BY created_at""", payments));
            bundle.append(SqlDump.of(ledger, "ledger holds of the test's payments", """
                    SELECT hold_id, account_id, amount_minor, status, entry_id, finalised_at, tombstone FROM holds
                     WHERE hold_id = ANY (?) ORDER BY created_at""", payments));
        }
        bundle.append("== recorded records of the test's payments\n");
        for (UUID payment : payments) {
            for (String topic : List.of(TopicRecorder.PAYMENTS_EVENTS, TopicRecorder.CSM_OUTBOUND_COMMANDS)) {
                RECORDER.mentioning(topic, payment.toString()).forEach(record ->
                        bundle.append(topic).append('\t').append(record.key()).append('\t').append(record.type())
                                .append('\n'));
            }
        }
        bundle.append("== dead letters\n").append(RECORDER.deadLetters().stream().map(Object::toString)
                .collect(Collectors.joining("\n"))).append('\n');
        return bundle.toString();
    }
}
