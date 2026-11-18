package com.tpe.systemtests.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.systemtests.harness.Bounds;
import com.tpe.systemtests.harness.Chaos;
import com.tpe.systemtests.harness.KafkaProduceProxy;
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
import java.util.List;
import java.util.Map;
import java.util.Queue;
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
 * The failure-injection scenarios of M3 (specs/20-testing-strategy.md §5): a csm-gateway instance killed mid-send
 * (CH-02) and every payment-orchestrator cut off from producing to Kafka around {@code sendBy} (CH-03), each under a
 * steady stream of payments to the peer bank, with the per-test invariants over all of them afterwards (INV-5 among
 * them: no {@code pacs.008} after its {@code sendBy}). The chaos run of {@link Chaos}: default timings ({@code sendBy}
 * 10 s, the deadline 20 s after acceptance), two instances of each, on the compose stack until M5 brings the chaos
 * environment and moves these into the nightly job.
 */
@Tag(Chaos.TAG)
@EnabledIfSystemProperty(named = "tpe.chaos", matches = "true",
        disabledReason = "the chaos run: -Pchaos -Dtpe.local-stack=true, compose profile chaos")
class ChaosIT extends PaymentSystemTest {

    /** The steady load: two payments a second, both directions coming with M4 (20 §5 runs 20 a second in M5). */
    private static final Duration LOAD_PERIOD = Duration.ofMillis(500);
    /** Fewer than {@code VELOCITY_COUNT_1H} (20 an hour, specs/14-risk-service.md §6.1) per payer. */
    private static final int PAYMENTS_PER_PAYER = 15;
    private static final int PAYEES = 5;

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
     * The second csm-gateway is killed ({@code SIGKILL}) every 30 s while it has a {@code pacs.008} in flight to the
     * CSM, and started again; the first is always up. What the killed one had claimed is taken over by the other once
     * its 2 s lease runs out, before {@code sendBy}: every payment settles, none ends {@code AB03}, none goes into
     * investigation at its deadline. ({@code DEADLINE} answers are those of incoming payments, which arrive with M4.)
     */
    @Test
    void CH_02_aGatewayKilledMidSendLeavesItsTransmissionsToTheOtherBeforeSendBy() throws Exception {
        List<String> claimedWhenKilled = new ArrayList<>();
        secondCsmGatewayLink().delayResponses(SLOW_SEND);
        try (Load load = new Load("CH-02", FIRST_KILL.plus(KILL_EVERY.multipliedBy(KILLS)))) {
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
        KafkaProduceProxy kafka = KafkaProduceProxy.get();
        RejectedCount rejected = rejectedOutgoing("AB03");
        List<List<Double>> relaySamples = new ArrayList<>();
        Instant blockStart;
        Instant blockEnd;
        URI leader;
        try (Load load = new Load("CH-03", BEFORE_BLOCK.plus(BLOCK).plus(AFTER_BLOCK))) {
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

    /** What the load paid: the payment and its payer, whose token reads it. */
    private record Paid(UUID payment, Party payer) {}

    private final Queue<Paid> paid = new ConcurrentLinkedQueue<>();

    private List<Paid> paidSoFar() {
        return List.copyOf(paid);
    }

    /**
     * The steady stream of payments to the peer bank for {@code duration}: one every {@link #LOAD_PERIOD}, each on a
     * worker of its own, so a slow submit holds up no other; payers and payees made before it starts. Closing it
     * stops it; a submit that failed fails the test.
     */
    private final class Load implements AutoCloseable {

        private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
        private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        private final Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        private final AtomicInteger sent = new AtomicInteger();
        private final Instant until;

        Load(String scenario, Duration duration) {
            long payments = duration.toMillis() / LOAD_PERIOD.toMillis() + 1;
            List<Party> payers = new ArrayList<>();
            for (int i = 0; i < (payments + PAYMENTS_PER_PAYER - 1) / PAYMENTS_PER_PAYER; i++) {
                payers.add(customer(PAYMENTS_PER_PAYER * PEER_PAYMENT_MINOR));
            }
            List<Payee> payees = new ArrayList<>();
            for (int i = 0; i < PAYEES; i++) {
                payees.add(peerPayee("ACCEPT"));
            }
            until = Instant.now().plus(duration);
            ticker.scheduleAtFixedRate(() -> {
                if (Instant.now().isAfter(until)) {
                    return;
                }
                int n = sent.getAndIncrement();
                Party payer = payers.get(n % payers.size());
                Payee payee = payees.get(n % payees.size());
                workers.execute(() -> {
                    try {
                        paid.add(new Paid(paidToPeer(payer, payee, scenario + "-" + n), payer));
                    } catch (Throwable e) {
                        failures.add(e);
                    }
                });
            }, 0, LOAD_PERIOD.toMillis(), TimeUnit.MILLISECONDS);
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
