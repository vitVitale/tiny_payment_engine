package com.tpe.systemtests.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.invariantchecker.EventInvariants;
import com.tpe.invariantchecker.PaymentInvariants;
import com.tpe.invariantchecker.Violation;
import java.net.URI;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * ledger-service, each told its neighbours' ports, the vop and risk projections fed by the ledger's real
 * {@code accounts.events}; a {@link TopicRecorder} on {@code payments.events}, {@code csm.outbound.commands} and the
 * dead-letter topics. After every test, beside the ledger's checks, the per-test invariants over the payments the test
 * initiated (specs/20-testing-strategy.md §3): INV-3, INV-7, INV-8, INV-9, INV-11 (orchestrator part). INV-13 (webhooks)
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

    static {
        var topics = new ArrayList<>(List.of(TopicRecorder.PAYMENTS_EVENTS, TopicRecorder.CSM_OUTBOUND_COMMANDS));
        topics.addAll(TopicRecorder.DEAD_LETTER_TOPICS);
        RECORDER = TopicRecorder.start("localhost:9092", topics);
        URI ledger = ServiceProcess.of(ServiceJar.LEDGER).baseUrl();
        URI risk = ServiceProcess.of(ServiceJar.RISK).baseUrl();
        VOP = ServiceProcess.of(ServiceJar.VOP).baseUrl();
        LEDGER = new JsonHttp(ledger);
        ORCHESTRATOR = ServiceProcess.of(ServiceJar.ORCHESTRATOR, Map.of(
                "tpe.clients.risk.base-url", risk.toString(),
                "tpe.clients.ledger.base-url", ledger.toString())).baseUrl();
        PAYMENT_API = new JsonHttp(ServiceProcess.of(ServiceJar.PAYMENT_API, Map.of(
                "tpe.clients.orchestrator.base-url", ORCHESTRATOR.toString(),
                "tpe.clients.vop.base-url", VOP.toString())).baseUrl());
    }

    /** The payments this test initiated: the scope of its invariant checks. */
    private final List<UUID> payments = new CopyOnWriteArrayList<>();

    /**
     * After the test, at quiescence (20 §3): INV-3, -7, -8, -9, -11. Quiescence comes by the payments' deadlines: a
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
        assertThat(paymentViolations()).as("INV-3, INV-7, INV-8, INV-9, INV-11 over %s", payments).isEmpty();
    }

    /** Every final event of the test's payments was read a whole delivery bound ago: a duplicate would be here. */
    private boolean finalEventsSettled() {
        Instant settled = Instant.now().minus(Bounds.eventDelivery());
        return payments.stream().map(RECORDER::firstFinalEventReadAt)
                .allMatch(first -> first.map(read -> read.isBefore(settled)).orElse(true));
    }

    private List<Violation> paymentViolations() throws SQLException {
        try (var orchestrator = LocalDatabases.payments(); var ledger = LocalDatabases.ledger()) {
            Map<UUID, String> states = PaymentInvariants.states(orchestrator, payments);
            List<Violation> violations = new ArrayList<>();
            violations.addAll(PaymentInvariants.holdsMatchOutcomes(orchestrator, ledger, payments));
            violations.addAll(PaymentInvariants.csmSettlementPostings(orchestrator, ledger, payments));
            violations.addAll(PaymentInvariants.noUnexpectedCases(orchestrator, payments, expectedCases()));
            violations.addAll(PaymentInvariants.nothingStuck(orchestrator, payments, Bounds.actionOverdue()));
            violations.addAll(EventInvariants.finalEvents(states, RECORDER.finalEvents(payments)));
            violations.addAll(EventInvariants.noDeadLetters(RECORDER.deadLetters()));
            return violations;
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
        return initiate(payer.token(), Map.of(
                "endToEndId", "SYSTEST-" + Long.toHexString(System.nanoTime()),
                "debtor", Map.of("name", payer.name(), "iban", payer.iban()),
                "creditor", Map.of("name", payeeName, "iban", payeeIban),
                "amount", Map.of("value", amount, "currency", "EUR"),
                "vopReference", vopReference));
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
        return new RejectedCount(reason, rejected(reason, "BUSINESS"), rejected(reason, "TECHNICAL"));
    }

    /** The counts of a reason under {@code BUSINESS} and {@code TECHNICAL} at one moment. */
    public record RejectedCount(String reason, double business, double technical) {

        /** The reason was counted under {@code cause} since this snapshot, and not under the other cause. */
        public void assertTaggedOnce(String cause) {
            RejectedCount now = rejectedOutgoing(reason);
            boolean business = cause.equals("BUSINESS");
            assertThat(business ? now.business() : now.technical())
                    .as("tpe.payment.rejected{reason=%s,cause=%s}", reason, cause)
                    .isGreaterThan(business ? business() : technical());
            assertThat(business ? now.technical() : now.business())
                    .as("tpe.payment.rejected{reason=%s} under the other cause", reason)
                    .isEqualTo(business ? technical() : business());
        }
    }

    private static double rejected(String reason, String cause) {
        return Scrape.value(ORCHESTRATOR, "tpe_payment_rejected_total",
                Map.of("direction", "OUTGOING", "reason", reason, "cause", cause));
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
        Response response = PAYMENT_API.post(authorization, "/v1/payments", payment);
        if (response.status() == 202) {
            payments.add(UUID.fromString(response.body().get("paymentId").asString()));
        }
        return response;
    }

    /** The payments this test initiated, in order. */
    protected List<UUID> payments() {
        return List.copyOf(payments);
    }

    /** The ledger's part, then the test's payments, their transitions, cases, postings and recorded records. */
    @Override
    public String debugBundle() throws SQLException {
        var bundle = new StringBuilder(super.debugBundle());
        try (var orchestrator = LocalDatabases.payments(); var ledger = LocalDatabases.ledger()) {
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
