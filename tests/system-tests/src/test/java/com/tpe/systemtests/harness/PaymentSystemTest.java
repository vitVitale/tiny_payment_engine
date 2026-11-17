package com.tpe.systemtests.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.invariantchecker.CsmInvariants;
import com.tpe.invariantchecker.EventInvariants;
import com.tpe.invariantchecker.PaymentInvariants;
import com.tpe.invariantchecker.Violation;
import com.tpe.paymentdomain.CommandId;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * invariants over the payments the test initiated (specs/20-testing-strategy.md §3): INV-3, INV-5, INV-7, INV-8, INV-9,
 * INV-10, INV-11 (orchestrator and gateway parts). INV-13 (webhooks) comes with the notifications track.
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
    private static final SimulatorControl SIMULATOR;
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
        CSM_GATEWAY = ServiceProcess.of(ServiceJar.CSM_GATEWAY, Map.of(
                "tpe.csm.base-url", simulator.toString()), gatewayPort);
        GATEWAY_ADMIN = new JsonHttp(CSM_GATEWAY.baseUrl());
        VOP = ServiceProcess.of(ServiceJar.VOP, Map.of(
                "tpe.bank-directory.location", BankDirectoryFile.withSimulator(simulator))).baseUrl();
        LEDGER = new JsonHttp(ledger);
        ORCHESTRATOR = ServiceProcess.of(ServiceJar.ORCHESTRATOR, Map.of(
                "tpe.clients.risk.base-url", risk.toString(),
                "tpe.clients.ledger.base-url", ledger.toString())).baseUrl();
        PAYMENT_API = new JsonHttp(ServiceProcess.of(ServiceJar.PAYMENT_API, Map.of(
                "tpe.clients.orchestrator.base-url", ORCHESTRATOR.toString(),
                "tpe.clients.vop.base-url", VOP.toString())).baseUrl());
    }

    /** The payments this test initiated: the scope of its invariant checks. */
    private final CopyOnWriteArrayList<UUID> payments = new CopyOnWriteArrayList<>();

    /**
     * After the test, at quiescence (20 §3): INV-3, -5, -7, -8, -9, -10, -11. Quiescence comes by the payments' deadlines: a
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
        assertThat(paymentViolations()).as("INV-3, -5, -7, -8, -9, -10, -11 over %s", payments).isEmpty();
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
            violations.addAll(PaymentInvariants.holdsMatchOutcomes(orchestrator, ledger, payments));
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

    /** The exception case types this test opens on purpose; any other is a breach of INV-9. None by default. */
    protected Set<String> expectedCases() {
        return Set.of();
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
        var status = new AtomicReference<JsonNode>();
        await().atMost(Bounds.paymentFinal()).pollInterval(Duration.ofMillis(100)).until(() -> {
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
        var counts = new HashMap<String, Double>();
        for (String cause : CAUSES) {
            counts.put(cause, rejected(reason, cause));
        }
        return new RejectedCount(reason, Map.copyOf(counts));
    }

    /** The {@code cause} tags of {@code tpe.payment.rejected} (25 §11): a business refusal, ours, the scheme's. */
    private static final List<String> CAUSES = List.of("BUSINESS", "TECHNICAL", "EXTERNAL");

    /** The counts of a reason under each {@code cause} at one moment. */
    public record RejectedCount(String reason, Map<String, Double> byCause) {

        /** The reason was counted under {@code cause} since this snapshot, and under no other cause. */
        public void assertTaggedOnce(String cause) {
            RejectedCount now = rejectedOutgoing(reason);
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

    private static double rejected(String reason, String cause) {
        return Scrape.value(ORCHESTRATOR, "tpe_payment_rejected_total",
                Map.of("direction", "OUTGOING", "reason", reason, "cause", cause));
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
     * The ledger's part, then the test's payments, their transitions, cases, postings and recorded records, and the
     * CSM side of its transfers: the gateway's records and journal, the simulator's received log.
     */
    @Override
    public String debugBundle() throws SQLException {
        var bundle = new StringBuilder(super.debugBundle());
        try (var orchestrator = LocalDatabases.payments(); var ledger = LocalDatabases.ledger();
                var gateway = LocalDatabases.csm()) {
            List<String> transfers = transfersToPeers(orchestrator);
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
