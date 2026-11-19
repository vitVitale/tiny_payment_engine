package com.tpe.csmgateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.tpe.csmgateway.IncomingPacs008.PEER_BIC;
import static com.tpe.csmgateway.IncomingPacs008.pacs008;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.tpe.contracts.AcceptCreditTransfer;
import com.tpe.contracts.Message;
import com.tpe.contracts.RejectCreditTransfer;
import com.tpe.csmgateway.application.outgoing.CreditTransferSending;
import com.tpe.iso20022model.CsmMessageType;
import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.EnvelopeCodec.Accepted;
import com.tpe.messaging.MessageHeaders;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.TxId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.web.client.RestClient;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * GW-AC-12 (specs/15-csm-gateway.md §6.3, §8, ADR-004): the answer coordinator holds under concurrency. Two gateway
 * instances on one database and one Kafka, both running the deadline worker, both consuming verdicts (the commands
 * topic has partitions for each), on the real clock; WireMock as the CSM records every answer {@code pacs.002}. Every
 * incoming transfer's verdict ({@code ACCP} or {@code RJCT}, at random) is to arrive at a moment drawn uniformly
 * from {@code [deadline − 50 ms, deadline + 50 ms]}, so verdicts and deadlines race on both instances: it is published
 * that moment less the delivery latency measured up front. Under load verdicts still arrive somewhat later; the run
 * prints where they did arrive, and checks that most arrived in the window, that both sides won some, and that each
 * instance answered both by verdict and by deadline.
 *
 * <p>Its own stack, not {@link GatewayStack}'s: that context's frozen clock and its listener in the same consumer
 * group would be a third instance on the topic.
 */
class AnswerRaceIT {

    private static final int TRANSFERS = 1_000;
    /** Receipts are paced, so the verdicts' race is spread over seconds rather than piled into one burst. */
    private static final Duration RECEIPT_PACE = Duration.ofMillis(20);
    private static final int WINDOW_MS = 50;
    private static final int COMMAND_PARTITIONS = 4;
    /** Verdicts timed to learn how long one takes to reach a gateway. */
    private static final int PROBES = 20;
    private static final Duration ANSWERED = Duration.ofSeconds(60);
    /** Past a claim's lease (2 s) and the answer's transmit window (1 s): a second send would show by then. */
    private static final Duration QUIET = Duration.ofSeconds(4);

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");
    /** Allows the gateway's session of 3 s (specs/15-csm-gateway.md §4): the broker's default minimum is 6 s. */
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0")
            .withEnv("KAFKA_GROUP_MIN_SESSION_TIMEOUT_MS", "3000");
    private static final WireMockServer CSM = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    private static final List<ConfigurableApplicationContext> INSTANCES = new ArrayList<>();

    private final EnvelopeCodec envelopes = new EnvelopeCodec();

    @BeforeAll
    static void twoInstances() throws Exception {
        POSTGRES.start();
        KAFKA.start();
        CSM.start();
        CSM.stubFor(post("/csm/v1/messages").willReturn(aResponse().withStatus(202)));
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(CreditTransferSending.COMMANDS, COMMAND_PARTITIONS, (short) 1)))
                    .all().get(30, TimeUnit.SECONDS);
        }
        for (int i = 0; i < 2; i++) {
            INSTANCES.add(new SpringApplicationBuilder(CsmGatewayApplication.class, StaffTokens.Config.class)
                    // as arguments, so they win over application.yml
                    .run("--server.port=0",
                            "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                            "--spring.datasource.username=" + POSTGRES.getUsername(),
                            "--spring.datasource.password=" + POSTGRES.getPassword(),
                            "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                            "--tpe.csm.base-url=" + CSM.baseUrl(),
                            "--management.tracing.export.enabled=false",
                            // two contexts in one JVM: no shared JMX names
                            "--spring.jmx.enabled=false"));
        }
        // both take verdicts: each instance holds some of the commands topic's partitions
        await().atMost(Duration.ofSeconds(60))
                .until(() -> INSTANCES.stream().allMatch(AnswerRaceIT::consumesCommands));
    }

    @AfterAll
    static void stop() {
        INSTANCES.forEach(ConfigurableApplicationContext::close);
        CSM.stop();
    }

    @Test
    void GW_AC_12_verdictsRacingTheDeadlineOnTwoInstancesGiveExactlyOneAnswerAndOneAgreeingAnswerSentPerTxId()
            throws Exception {
        JdbcClient jdbc = JdbcClient.create(INSTANCES.getFirst().getBean(DataSource.class));
        JsonMapper json = INSTANCES.getFirst().getBean(JsonMapper.class);
        List<RestClient> gateways = INSTANCES.stream().map(AnswerRaceIT::http).toList();
        Map<String, String> verdicts = new ConcurrentHashMap<>();
        Set<Throwable> publishFailures = ConcurrentHashMap.newKeySet();

        ScheduledExecutorService verdictTimer = Executors.newScheduledThreadPool(4);
        Duration lead;
        try (var producer = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.LINGER_MS_CONFIG, 0))) {
            lead = deliveryLatency(producer, json, jdbc);
            long paced = System.nanoTime();
            for (int i = 0; i < TRANSFERS; i++) {
                String txId = "TX-RACE-" + i;
                Instant now = Instant.now();
                assertThat(gateways.get(i % 2).post().uri("/csm/v1/inbound").contentType(MediaType.APPLICATION_XML)
                        .body(pacs008("M-RACE-" + i, txId, PEER_BIC, now)).retrieve().toBodilessEntity()
                        .getStatusCode().value()).as("receipt of %s", txId).isEqualTo(202);
                Instant deadline = jdbc.sql("""
                        SELECT answer_deadline FROM incoming_transfers
                         WHERE debtor_agent_bic = :bic AND tx_id = :txId""")
                        .param("bic", PEER_BIC.value()).param("txId", txId).query(Timestamp.class).single()
                        .toInstant();
                boolean accept = ThreadLocalRandom.current().nextBoolean();
                verdicts.put(txId, accept ? "ACCP" : "RJCT");
                // published this much early, so it arrives in the window
                Instant at = deadline.plusMillis(ThreadLocalRandom.current().nextInt(-WINDOW_MS, WINDOW_MS + 1))
                        .minus(lead);
                Message verdict = accept
                        ? new AcceptCreditTransfer(new PaymentId(UUID.randomUUID()), new TxId(txId), PEER_BIC, now)
                        : new RejectCreditTransfer(new PaymentId(UUID.randomUUID()), new TxId(txId), PEER_BIC, now,
                                ReasonCode.AC06);
                verdictTimer.schedule(() -> {
                    try {
                        producer.send(command(json, txId, verdict));
                    } catch (RuntimeException e) {
                        publishFailures.add(e);
                    }
                }, Duration.between(Instant.now(), at).toNanos(), TimeUnit.NANOSECONDS);
                paced += RECEIPT_PACE.toNanos();
                long ahead = paced - System.nanoTime();
                if (ahead > 0) {
                    TimeUnit.NANOSECONDS.sleep(ahead);
                }
            }

            await().atMost(ANSWERED).untilAsserted(() -> assertThat(jdbc.sql("""
                    SELECT coalesce(answer_status, 'none') || ' ' || coalesce(transmit_status, 'none') AS state,
                           count(*) AS n
                      FROM incoming_transfers GROUP BY 1""")
                    .query((rs, n) -> Map.entry(rs.getString("state"), rs.getLong("n"))).list())
                    .as("every transfer answered and its answer delivered")
                    .allSatisfy(state -> assertThat(state.getKey()).matches("(ACCP|RJCT) DELIVERED")));
            verdictTimer.shutdown();
            assertThat(verdictTimer.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            producer.flush();
        } finally {
            verdictTimer.shutdownNow();
        }
        assertThat(publishFailures).isEmpty();
        // the last verdicts are consumed, and nothing else is sent, journalled or written
        await().atMost(ANSWERED).until(() -> ignoredVerdicts(jdbc).size() == deadlineAnswers(jdbc));
        await().during(QUIET).atMost(ANSWERED).until(() -> answersSent().size() == TRANSFERS);

        Map<String, Row> rows = jdbc.sql("""
                SELECT tx_id, answer_status, answer_reason, answer_decided_by, answer_msg_id FROM incoming_transfers""")
                .query((rs, n) -> new Row(rs.getString("tx_id"), rs.getString("answer_status"),
                        rs.getString("answer_reason"), rs.getString("answer_decided_by"),
                        rs.getString("answer_msg_id")))
                .list().stream().collect(toMap(Row::txId, Function.identity()));
        assertThat(rows).hasSize(TRANSFERS);

        List<Answer> answers = answersSent();
        onePerTransaction(answers.stream().map(a -> a.debtorAgentBic() + "/" + a.txId()), "answer pacs.002");
        List<Event> events = jdbc.sql("""
                SELECT message_key, payload->>'debtorAgentBic' AS bic, payload->>'status' AS status,
                       payload->>'reasonCode' AS reason, payload->>'decidedBy' AS decided_by
                  FROM outbox WHERE topic = 'csm.inbound.events' AND message_type = 'AnswerSent'""")
                .query((rs, n) -> new Event(rs.getString("message_key"), rs.getString("bic"),
                        rs.getString("status"), rs.getString("reason"), rs.getString("decided_by")))
                .list();
        onePerTransaction(events.stream().map(e -> e.debtorAgentBic() + "/" + e.txId()), "AnswerSent");

        Map<String, Event> eventOf = events.stream()
                .collect(toMap(Event::txId, Function.identity()));
        for (Answer answer : answers) {
            Row row = rows.get(answer.txId());
            Event event = eventOf.get(answer.txId());
            assertThat(answer.debtorAgentBic()).isEqualTo(PEER_BIC.value());
            assertThat(answer.msgId()).as("the answer decided for %s", answer.txId()).isEqualTo(row.msgId());
            // the pacs.002 carries no decidedBy: the answer's is the row's it was built from
            assertThat(new Decision(answer.status(), answer.reason(), row.decidedBy()))
                    .as("the answer and AnswerSent of %s agree", answer.txId())
                    .isEqualTo(event.decision()).isEqualTo(row.decision());
            if ("VERDICT".equals(row.decidedBy())) {
                assertThat(row.status()).as("%s answered with its verdict", answer.txId())
                        .isEqualTo(verdicts.get(answer.txId()));
                assertThat(row.reason()).isEqualTo("ACCP".equals(row.status()) ? null : "AC06");
            } else {
                assertThat(row.decision()).isEqualTo(new Decision("RJCT", "AB05", "DEADLINE"));
            }
        }

        long byDeadline = deadlineAnswers(jdbc);
        // when each verdict reached a gateway, relative to its deadline: the answer's moment if it won, else the
        // moment it was ignored
        Map<String, Object> arrivals = jdbc.sql("""
                WITH arrival AS (
                  SELECT extract(epoch FROM coalesce(j.at, t.answered_at) - t.answer_deadline) * 1000 AS ms
                    FROM incoming_transfers t
                    LEFT JOIN message_journal j ON j.tx_id = t.tx_id AND j.note = 'verdict ignored')
                SELECT round(percentile_cont(0.5) WITHIN GROUP (ORDER BY ms)::numeric, 1) AS median,
                       count(*) FILTER (WHERE ms BETWEEN -:window AND :window) AS in_window
                  FROM arrival""").param("window", WINDOW_MS).query().singleRow();
        System.out.printf("GW-AC-12: verdicts published %d ms early; %d of %d arrived within ±%d ms of the deadline,"
                + " median %s ms; %d answered by VERDICT, %d by DEADLINE%n", lead.toMillis(),
                arrivals.get("in_window"), TRANSFERS, WINDOW_MS, arrivals.get("median"), TRANSFERS - byDeadline,
                byDeadline);
        assertThat(byDeadline).as("the race is run: both win some").isBetween(1L, TRANSFERS - 1L);
        assertThat(((Number) arrivals.get("in_window")).intValue()).as("most verdicts arrive in the window")
                .isGreaterThan(TRANSFERS / 2);
        for (ConfigurableApplicationContext instance : INSTANCES) {
            assertThat(answersDecided(instance, "VERDICT")).as("verdicts answered on each instance").isPositive();
            assertThat(answersDecided(instance, "DEADLINE")).as("deadlines answered on each instance").isPositive();
        }
        assertThat(ignoredVerdicts(jdbc)).as("every verdict that lost to its deadline is ignored, and no other")
                .hasSize((int) byDeadline)
                .allSatisfy(txId -> assertThat(rows.get(txId).decidedBy()).isEqualTo("DEADLINE"));
    }

    /**
     * How long a verdict takes from its publication to its handling on a gateway, the median of {@code PROBES}
     * verdicts for no known transfer (each journalled {@code verdict ignored} at the moment it is handled).
     */
    private static Duration deliveryLatency(KafkaProducer<String, String> producer, JsonMapper json,
            JdbcClient jdbc) throws Exception {
        List<Duration> latencies = new ArrayList<>();
        for (int i = 0; i < PROBES; i++) {
            String txId = "TX-PROBE-" + i;
            Instant published = Instant.now();
            producer.send(command(json, txId, new AcceptCreditTransfer(new PaymentId(UUID.randomUUID()),
                    new TxId(txId), PEER_BIC, published))).get(10, TimeUnit.SECONDS);
            Instant handled = await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(5))
                    .until(() -> jdbc.sql("SELECT at FROM message_journal WHERE tx_id = :txId")
                            .param("txId", txId).query(Timestamp.class).optional(), Optional::isPresent)
                    .orElseThrow().toInstant();
            latencies.add(Duration.between(published, handled));
        }
        latencies.sort(null);
        return latencies.get(PROBES / 2);
    }

    /** Whether {@code instance}'s commands listener has partitions assigned. */
    private static boolean consumesCommands(ConfigurableApplicationContext instance) {
        return instance.getBean(KafkaListenerEndpointRegistry.class).getListenerContainers().stream()
                .anyMatch(container -> container.getAssignedPartitions() != null && container.getAssignedPartitions()
                        .stream().anyMatch(p -> p.topic().equals(CreditTransferSending.COMMANDS)));
    }

    /** {@code keys} hold every transaction once, and only once. */
    private static void onePerTransaction(Stream<String> keys, String what) {
        assertThat(keys.collect(groupingBy(Function.identity(), counting())))
                .as("one %s per (DbtrAgt, TxId)", what).hasSize(TRANSFERS)
                .allSatisfy((key, count) -> assertThat(count).as(key).isEqualTo(1L));
    }

    /** The answers {@code instance} decided, by {@code decidedBy} ({@code tpe.csm.answer}). */
    private static double answersDecided(ConfigurableApplicationContext instance, String decidedBy) {
        return instance.getBean(MeterRegistry.class).find("tpe.csm.answer").tag("decidedBy", decidedBy).counters()
                .stream().mapToDouble(Counter::count).sum();
    }

    /** A client for {@code instance}'s port that never throws on an error status. */
    private static RestClient http(ConfigurableApplicationContext instance) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + instance.getEnvironment().getProperty("local.server.port"))
                .defaultStatusHandler(status -> true, (request, response) -> {})
                .build();
    }

    /** As the orchestrator's relay publishes a verdict on {@code csm.outbound.commands}. */
    private static ProducerRecord<String, String> command(JsonMapper json, String key, Message message) {
        var record = new ProducerRecord<>(CreditTransferSending.COMMANDS, key, json.writeValueAsString(message));
        var contract = Message.contractOf(message.getClass());
        record.headers().add(MessageHeaders.EVENT_ID, bytes(UUID.randomUUID().toString()));
        record.headers().add(MessageHeaders.MESSAGE_TYPE, bytes(contract.type()));
        record.headers().add(MessageHeaders.SCHEMA_VERSION, bytes(Integer.toString(contract.version())));
        return record;
    }

    /** Every answer {@code pacs.002} the CSM stub took. */
    private List<Answer> answersSent() {
        return CSM.findAll(postRequestedFor(urlEqualTo("/csm/v1/messages"))).stream()
                .map(LoggedRequest::getBodyAsString).map(this::answer).toList();
    }

    /** What an answer {@code pacs.002} says, and of which transaction. */
    private Answer answer(String xml) {
        var envelope = ((Accepted) envelopes.split(xml)).envelope();
        assertThat(envelope.messageType()).isEqualTo(CsmMessageType.PACS_002);
        var report = ((com.tpe.iso20022model.pacs002v10.Document) envelope.document()).getFIToFIPmtStsRpt();
        var transaction = report.getTxInfAndSts().getFirst();
        return new Answer(transaction.getOrgnlTxRef().getDbtrAgt().getFinInstnId().getBICFI(),
                transaction.getOrgnlTxId(), report.getGrpHdr().getMsgId(), transaction.getTxSts(),
                transaction.getStsRsnInf().isEmpty() ? null
                        : transaction.getStsRsnInf().getFirst().getRsn().getCd());
    }

    /** The transfers answered by their deadline. */
    private static long deadlineAnswers(JdbcClient jdbc) {
        return jdbc.sql("SELECT count(*) FROM incoming_transfers WHERE answer_decided_by = 'DEADLINE'")
                .query(Long.class).single();
    }

    /** The transactions of the race whose verdict was journalled {@code verdict ignored}. */
    private static List<String> ignoredVerdicts(JdbcClient jdbc) {
        return jdbc.sql("""
                SELECT tx_id FROM message_journal WHERE note = 'verdict ignored' AND tx_id LIKE 'TX-RACE-%'""")
                .query(String.class).list();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** An {@code incoming_transfers} row's answer. */
    private record Row(String txId, String status, String reason, String decidedBy, String msgId) {

        Decision decision() {
            return new Decision(status, reason, decidedBy);
        }
    }

    /** An answer {@code pacs.002} the CSM took. */
    private record Answer(String debtorAgentBic, String txId, String msgId, String status, String reason) {}

    /** An {@code AnswerSent} written to the outbox. */
    private record Event(String txId, String debtorAgentBic, String status, String reason, String decidedBy) {

        Decision decision() {
            return new Decision(status, reason, decidedBy);
        }
    }

    /** What an answer says: status, reason, and who decided it. */
    private record Decision(String status, String reason, String decidedBy) {}
}
