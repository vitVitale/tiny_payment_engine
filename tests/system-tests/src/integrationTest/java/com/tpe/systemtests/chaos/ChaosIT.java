package com.tpe.systemtests.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.systemtests.harness.Bounds;
import com.tpe.systemtests.harness.Chaos;
import com.tpe.systemtests.harness.KafkaProxy;
import com.tpe.systemtests.harness.LocalDatabases;
import com.tpe.systemtests.harness.PaymentSystemTest;
import com.tpe.systemtests.harness.Scrape;
import com.tpe.systemtests.harness.ServiceJar;
import com.tpe.systemtests.harness.ServiceProfile;
import java.net.URI;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;

/**
 * The failure-injection scenarios of M3 and M4 (specs/20-testing-strategy.md §5): a csm-gateway instance killed
 * mid-send (CH-02), every payment-orchestrator cut off from producing to Kafka around {@code sendBy} (CH-03), and every
 * gateway cut off from consuming its commands past the answer deadline (CH-04), each under a steady stream of payments
 * to the peer bank, from it, or both, with the per-test invariants over all of them afterwards (INV-5 among them: no
 * {@code pacs.008} after its {@code sendBy}; INV-6: exactly one answer per incoming transaction, the orchestrator
 * agreeing with it). The chaos run of {@link Chaos}: default timings ({@code sendBy} 10 s, the deadline 20 s after
 * acceptance, the answer deadline 5 s after receipt), two instances of each, on the compose stack until M5 brings the
 * chaos environment and moves these into the nightly job.
 */
@Tag(Chaos.TAG)
@EnabledIfSystemProperty(named = "tpe.chaos", matches = "true",
        disabledReason = "the chaos run: -Pchaos -Dtpe.local-stack=true, compose profile chaos")
class ChaosIT extends PaymentSystemTest {

    /** The steady load: two payments a second in each direction it has (20 §5 runs 20 a second in M5). */
    private static final Duration LOAD_PERIOD = Duration.ofMillis(500);
    /** Fewer than {@code VELOCITY_COUNT_1H} (20 an hour, specs/14-risk-service.md §6.1) per payer. */
    private static final int PAYMENTS_PER_PAYER = 15;
    private static final int PAYEES = 5;
    /** Not more than {@code MULE_NEW_ACCOUNT} lets a new account receive (10 in 24 h, 14 §6.1). */
    private static final int INCOMING_PER_PAYEE = 10;
    private static final String INCOMING_AMOUNT = "12.34";

    /** CH-02: the kills, 30 s apart, the first once the load runs. */
    private static final Duration FIRST_KILL = Duration.ofSeconds(10);
    private static final Duration KILL_EVERY = Duration.ofSeconds(30);
    private static final int KILLS = 2;
    /** How long the second gateway's sends take: in flight when it is killed, within its read timeout. */
    private static final Duration SLOW_SEND = Duration.ofMillis(700);

    /**
     * CH-03: some payments settle before the block; the block of §5; the load goes on until the payments accepted at
     * its end are past their {@code sendBy} and their backlog published.
     */
    private static final Duration BEFORE_BLOCK = Duration.ofSeconds(5);
    private static final Duration BLOCK = Duration.ofSeconds(15);
    private static final Duration AFTER_BLOCK = ServiceProfile.systemtest(ServiceJar.ORCHESTRATOR)
            .duration("tpe.outgoing.send-by-offset").orElseThrow().plus(Bounds.orchestratorEventDelivery());
    private static final Duration RELAY_SAMPLE = Duration.ofMillis(250);
    /** CH-02 kills once the slow link has a send in flight: some of the next twenty payments go that way. */
    private static final int PAYMENTS_TO_A_SLOW_SEND = 20;

    /**
     * CH-04: some payments answered before the cut; the cut, longer than the answer deadline and the transmit window,
     * so the verdicts produced in it are consumed only after them; the load goes on until the payments received at its
     * end have been answered and their late verdicts consumed.
     */
    private static final Duration BEFORE_CUT = Duration.ofSeconds(5);
    private static final Duration CUT = Duration.ofSeconds(15);
    private static final Duration AFTER_CUT = Bounds.verdictHeldPastAnswer().plus(Bounds.csmEventHandled());

    /**
     * The second csm-gateway is killed ({@code SIGKILL}) every 30 s while it has a {@code pacs.008} in flight to the
     * CSM, and started again; the first is always up. What the killed one had claimed is taken over by the other once
     * its 2 s lease runs out, before {@code sendBy}: every payment settles, none ends {@code AB03}, none goes into
     * investigation at its deadline. The load has incoming payments as well: each is answered by our verdict, none by
     * the gateway's answer deadline ({@code DEADLINE}), and settled. Not so yet: the verdicts on the killed instance's
     * partitions of {@code csm.outbound.commands} wait for its consumer's session timeout, which is longer than the
     * answer deadline, so the few received at a kill are answered {@code DEADLINE} (issue #158).
     */
    @Test
    void CH_02_aGatewayKilledMidSendLeavesItsTransmissionsToTheOtherBeforeSendBy() throws Exception {
        List<String> claimedWhenKilled = new ArrayList<>();
        secondCsmGatewayLink().delayResponses(SLOW_SEND);
        try (Load load = new Load("CH-02", FIRST_KILL.plus(KILL_EVERY.multipliedBy(KILLS)),
                EnumSet.of(Flow.OUTGOING, Flow.INCOMING))) {
            Instant started = Instant.now();
            for (int kill = 0; kill < KILLS; kill++) {
                awaitInstant(started.plus(FIRST_KILL).plus(KILL_EVERY.multipliedBy(kill)));
                await().atMost(LOAD_PERIOD.multipliedBy(PAYMENTS_TO_A_SLOW_SEND)).pollInterval(Duration.ofMillis(20))
                        .until(() -> !slowClaims().isEmpty());
                claimedWhenKilled.addAll(slowClaims());
                secondCsmGateway().kill();
                secondCsmGateway().start();
            }
            load.awaitDone();
        } finally {
            secondCsmGatewayLink().heal();
        }

        for (Paid paid : paidSoFar()) {
            JsonNode status = finalStatus(paid.payer(), paid.payment(), Bounds.paymentFinal());
            assertThat(status.get("status").asString()).as("%s: %s", paid.payment(), status).isEqualTo("ACSC");
            assertThat(transitions(paid.payment())).as("%s", paid.payment()).noneMatch(t -> t.endsWith("|DEADLINE"));
        }
        List<Transmission> claimed = transmissions(claimedWhenKilled);
        assertThat(claimed).as("claimed when killed").allSatisfy(transmission -> {
            assertThat(transmission.status()).isEqualTo("DELIVERED");
            assertThat(transmission.deliveredAt()).isBefore(transmission.sendBy());
        });
        assertThat(claimed).as("taken over by the other instance after the lease")
                .anyMatch(transmission -> transmission.attempts() > 1);
        assertThat(receivedSoFar()).as("incoming payments of the load").isNotEmpty();
        List<IncomingTransfer> byDeadline = new ArrayList<>();
        for (IncomingTransfer transfer : receivedSoFar()) {
            String state = finalState(transfer.payment(), Bounds.paymentFinal());
            if ("DEADLINE".equals(answerDecidedBy(transfer))) {
                byDeadline.add(transfer);
                continue;
            }
            assertThat(state).as("%s answered by our verdict", transfer).isEqualTo("SETTLED");
        }
        // a killed instance's command partitions wait for its consumer's session timeout, past the answer deadline:
        // issue #158 decides whether the gateway's failover gets faster or the scenario's expectation changes
        assertThat(byDeadline).as("incoming payments of the load answered by DEADLINE (#158)").isEmpty();
    }

    /**
     * Every payment-orchestrator is cut off from producing to Kafka for 15 s (its consumers keep reading): no
     * {@code pacs.008} leaves after its {@code sendBy} (INV-5). A payment whose {@code SendCreditTransfer} the block
     * held past its {@code sendBy} (accepted in the block, {@code sendBy} before its end) ends {@code RJCT AB03},
     * {@code NOT_SENT} at the gateway, nothing at the CSM, tagged {@code TECHNICAL}; one accepted before the block was
     * produced before it, and may settle although its {@code sendBy} fell in it. Any other rejection is such an
     * {@code AB03} of a payment published too late. The relay's leadership stays where it was: a failed send counts as progress (ADR-006 amendment), so
     * {@code tpe.relay.active} is 1 on the same one instance throughout.
     */
    @Test
    void CH_03_ordersCutOffFromKafkaPastSendByEndAb03AndTheRelayKeepsItsLeader() throws Exception {
        KafkaProxy kafka = KafkaProxy.orchestratorProduce();
        RejectedCount rejected = rejectedOutgoing("AB03");
        List<List<Double>> relaySamples = new ArrayList<>();
        Instant blockStart;
        Instant blockEnd;
        URI leader;
        try (Load load = new Load("CH-03", BEFORE_BLOCK.plus(BLOCK).plus(AFTER_BLOCK), EnumSet.of(Flow.OUTGOING))) {
            Instant started = Instant.now();
            leader = awaitOneRelayLeader();
            awaitInstant(started.plus(BEFORE_BLOCK));
            blockStart = Instant.now();
            kafka.block();
            try {
                while (Instant.now().isBefore(blockStart.plus(BLOCK))) {
                    relaySamples.add(relayActive());
                    awaitInstant(Instant.now().plus(RELAY_SAMPLE));
                }
            } finally {
                kafka.unblock();
                blockEnd = Instant.now();
            }
            load.awaitDone();
        }

        int leaderIndex = orchestratorServices().indexOf(leader);
        assertThat(relaySamples).as("tpe.relay.active of %s during the block", orchestratorServices())
                .isNotEmpty().allSatisfy(sample -> {
                    assertThat(sample.get(leaderIndex)).isEqualTo(1.0);
                    assertThat(sample.stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(1.0);
                });
        // published as soon as Kafka is reachable again: a sendBy later than this was met
        Instant caughtUp = blockEnd.plus(Bounds.orchestratorEventDelivery());
        int cutOff = 0;
        for (Paid paid : paidSoFar()) {
            JsonNode status = finalStatus(paid.payer(), paid.payment(), Bounds.paymentFinal());
            OrchestratorPayment found = orchestratorPayment(paid.payment());
            boolean blockedPastSendBy = !found.acceptedAt().isBefore(blockStart) && found.sendBy().isBefore(blockEnd);
            if ("ACSC".equals(status.get("status").asString())) {
                assertThat(blockedPastSendBy).as("%s settled though its sendBy %s passed in the block", paid.payment(),
                        found.sendBy()).isFalse();
                continue;
            }
            assertThat(status.get("status").asString()).as("%s: %s", paid.payment(), status).isEqualTo("RJCT");
            assertThat(status.get("reasonCode").asString()).as("%s", paid.payment()).isEqualTo("AB03");
            assertThat(found.sendBy()).as("%s rejected AB03", paid.payment()).isBefore(caughtUp);
            assertThat(gatewaySendStatus(found.txId())).as("%s", paid.payment()).isEqualTo("NOT_SENT AB03");
            assertThat(simulator().received(found.txId())).as("%s at the CSM", paid.payment()).isEmpty();
            if (blockedPastSendBy) {
                cutOff++;
            }
        }
        // each of them was rejected AB03 above: none settled
        assertThat(cutOff).as("payments accepted in the block whose sendBy passed in it").isPositive();
        // of the instance payment-api calls; the other's counter moves too
        rejected.assertTaggedOnce("TECHNICAL");
    }

    /**
     * Every csm-gateway is cut off from consuming {@code csm.outbound.commands} for 15 s, under a stream of incoming
     * payments (they produce directly, and the CSM keeps calling the first): the verdicts produced in the cut are
     * consumed only after it, past the answer deadline (the answer race of ADR-004). A cut, not a latency: one longer
     * than the 5 s answer deadline would also hold back the consumers' heartbeats past their 6 s session, so the group
     * would churn for the whole window instead of resuming once. Whoever wins, INV-6 holds after the test: exactly one
     * answer per (debtor agent, {@code TxId}), and the orchestrator agrees with it. A payment answered by
     * {@code DEADLINE} ends {@code REJECTED AB05} with its pending credit cancelled, and its late verdict is ignored when
     * it is consumed; every other one is answered by our verdict and settled.
     */
    @Test
    void CH_04_verdictsConsumedPastTheAnswerDeadlineLeaveOneAnswerEach() throws Exception {
        KafkaProxy commands = KafkaProxy.gatewayConsume();
        try (Load load = new Load("CH-04", BEFORE_CUT.plus(CUT).plus(AFTER_CUT), EnumSet.of(Flow.INCOMING))) {
            awaitInstant(Instant.now().plus(BEFORE_CUT));
            commands.block();
            try {
                awaitInstant(Instant.now().plus(CUT));
            } finally {
                commands.unblock();
            }
            load.awaitDone();
        }

        int byDeadline = 0;
        for (IncomingTransfer transfer : receivedSoFar()) {
            String state = finalState(transfer.payment(), Bounds.paymentFinal());
            if (!"DEADLINE".equals(answerDecidedBy(transfer))) {
                assertThat(state).as("%s answered by our verdict", transfer).isEqualTo("SETTLED");
                continue;
            }
            byDeadline++;
            assertThat(state).as("%s answered by DEADLINE", transfer).isEqualTo("REJECTED");
            assertThat(orchestratorPayment(transfer.payment()).reasonCode()).as("%s", transfer).isEqualTo("AB05");
            await().atMost(Bounds.eventDelivery()).untilAsserted(() ->
                    assertThat(pendingCredit(transfer.payment()).body().get("status").asString()).as("%s", transfer)
                            .isEqualTo("CANCELLED"));
            await().atMost(Bounds.csmEventHandled()).untilAsserted(() ->
                    assertThat(gatewayNotes(transfer.txId())).as("%s: its late verdict", transfer)
                            .contains("verdict ignored"));
        }
        assertThat(byDeadline).as("payments whose verdict the cut held past the answer deadline").isPositive();
    }

    /** What the load paid: the payment and its payer, whose token reads it. */
    private record Paid(UUID payment, Party payer) {}

    private final Queue<Paid> paid = new ConcurrentLinkedQueue<>();

    private List<Paid> paidSoFar() {
        return List.copyOf(paid);
    }

    private final Queue<IncomingTransfer> received = new ConcurrentLinkedQueue<>();

    private List<IncomingTransfer> receivedSoFar() {
        return List.copyOf(received);
    }

    /** csm-gateway's {@code answer_decided_by} of the incoming transfer: {@code VERDICT}, {@code DEADLINE}, …. */
    private static String answerDecidedBy(IncomingTransfer transfer) throws SQLException {
        String answer = gatewayIncoming(transfer.debtorAgentBic(), transfer.txId());
        return answer == null ? null : answer.split(" ")[2];
    }

    /** The directions of a {@link Load}: payments to the peer bank, and from it to customers of ours. */
    private enum Flow { OUTGOING, INCOMING }

    /**
     * The steady stream of payments for {@code duration}: one in each of its {@code flows} every {@link #LOAD_PERIOD},
     * each on a worker of its own, so a slow submit holds up no other; payers and payees made before it starts.
     * Closing it stops it; a submit that failed fails the test.
     */
    private final class Load implements AutoCloseable {

        private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
        private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        private final Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        private final AtomicInteger sent = new AtomicInteger();
        private final Instant until;

        Load(String scenario, Duration duration, Set<Flow> flows) {
            boolean outgoing = flows.contains(Flow.OUTGOING);
            boolean incoming = flows.contains(Flow.INCOMING);
            long payments = duration.toMillis() / LOAD_PERIOD.toMillis() + 1;
            List<Party> payers = new ArrayList<>();
            List<Payee> payees = new ArrayList<>();
            if (outgoing) {
                for (int i = 0; i < (payments + PAYMENTS_PER_PAYER - 1) / PAYMENTS_PER_PAYER; i++) {
                    payers.add(customer(PAYMENTS_PER_PAYER * PEER_PAYMENT_MINOR));
                }
                for (int i = 0; i < PAYEES; i++) {
                    payees.add(peerPayee("ACCEPT"));
                }
            }
            List<Party> creditors = new ArrayList<>();
            if (incoming) {
                for (int i = 0; i < (payments + INCOMING_PER_PAYEE - 1) / INCOMING_PER_PAYEE; i++) {
                    creditors.add(customer(0));
                }
            }
            until = Instant.now().plus(duration);
            ticker.scheduleAtFixedRate(() -> {
                if (Instant.now().isAfter(until)) {
                    return;
                }
                int n = sent.getAndIncrement();
                if (outgoing) {
                    Party payer = payers.get(n % payers.size());
                    Payee payee = payees.get(n % payees.size());
                    submit(() -> paid.add(new Paid(paidToPeer(payer, payee, scenario + "-" + n), payer)));
                }
                if (incoming) {
                    Party creditor = creditors.get(n % creditors.size());
                    submit(() -> received.add(received(creditor.iban(), INCOMING_AMOUNT, "ACCEPT")));
                }
            }, 0, LOAD_PERIOD.toMillis(), TimeUnit.MILLISECONDS);
        }

        /** {@code submit} on a worker of its own; what it throws fails the test in {@link #awaitDone()}. */
        private void submit(Runnable submit) {
            workers.execute(() -> {
                try {
                    submit.run();
                } catch (Throwable e) {
                    failures.add(e);
                }
            });
        }

        /** Until its duration is over and every submit has been answered. */
        void awaitDone() {
            awaitInstant(until);
            ticker.shutdown();
            workers.shutdown();
            try {
                assertThat(workers.awaitTermination(Bounds.paymentFinal().toMillis(), TimeUnit.MILLISECONDS))
                        .as("submits answered").isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            assertThat(failures).as("submits of the load").isEmpty();
        }

        @Override
        public void close() {
            ticker.shutdownNow();
            workers.shutdownNow();
        }
    }

    /**
     * Paces the scenario: the injections at the instants of §5. Not a wait for the system, which the assertions bound,
     * so a plain sleep.
     */
    private static void awaitInstant(Instant instant) {
        Duration left = Duration.between(Instant.now(), instant);
        if (left.isNegative()) {
            return;
        }
        try {
            Thread.sleep(left);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** The orchestrator whose relay leads, once exactly one does: within a takeover, as an event's delivery allows. */
    private static URI awaitOneRelayLeader() {
        await().atMost(Bounds.orchestratorEventDelivery()).pollInterval(RELAY_SAMPLE)
                .until(() -> relayActive().stream().mapToDouble(Double::doubleValue).sum() == 1.0);
        List<Double> active = relayActive();
        return orchestratorServices().get(active.indexOf(1.0));
    }

    /** {@code tpe.relay.active} of each orchestrator, in {@link #orchestratorServices()} order. */
    private static List<Double> relayActive() {
        return orchestratorServices().stream()
                .map(service -> Scrape.value(service, "tpe_relay_active", Map.of())).toList();
    }

    /**
     * The transmissions claimed now ({@code PENDING} under a lease that has not run out) for a quarter of
     * {@link #SLOW_SEND} or longer: the second gateway's, in flight on its slow link; the first's last milliseconds.
     */
    private static List<String> slowClaims() throws SQLException {
        try (var gateway = LocalDatabases.csm(); var statement = gateway.prepareStatement("""
                SELECT tx_id FROM outgoing_transfers
                 WHERE transmit_status = 'PENDING' AND transmit_attempts > 0 AND next_transmit_at > now()
                   AND next_transmit_at <= now() + ? * interval '1 millisecond'""")) {
            statement.setLong(1, Bounds.transmissionLease().minus(SLOW_SEND.dividedBy(4)).toMillis());
            var claimed = new ArrayList<String>();
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    claimed.add(rs.getString(1));
                }
            }
            return claimed;
        }
    }

    /** A transmission of a {@code pacs.008} as the gateway recorded it. */
    private record Transmission(String txId, String status, int attempts, Instant deliveredAt, Instant sendBy) {}

    private static List<Transmission> transmissions(List<String> txIds) throws SQLException {
        try (var gateway = LocalDatabases.csm(); var statement = gateway.prepareStatement("""
                SELECT tx_id, transmit_status, transmit_attempts, delivered_at, send_by FROM outgoing_transfers
                 WHERE tx_id = ANY (?)""")) {
            statement.setArray(1, gateway.createArrayOf("varchar", txIds.toArray()));
            var found = new ArrayList<Transmission>();
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    var delivered = rs.getTimestamp(4);
                    found.add(new Transmission(rs.getString(1), rs.getString(2), rs.getInt(3),
                            delivered == null ? null : delivered.toInstant(), rs.getTimestamp(5).toInstant()));
                }
            }
            return found;
        }
    }

    /** csm-gateway's {@code send_status} and {@code not_sent_reason} of the transaction; {@code null} if unknown. */
    private static String gatewaySendStatus(String txId) throws SQLException {
        try (var gateway = LocalDatabases.csm(); var statement = gateway.prepareStatement(
                "SELECT send_status || coalesce(' ' || not_sent_reason, '') FROM outgoing_transfers WHERE tx_id = ?")) {
            statement.setString(1, txId);
            try (var rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
