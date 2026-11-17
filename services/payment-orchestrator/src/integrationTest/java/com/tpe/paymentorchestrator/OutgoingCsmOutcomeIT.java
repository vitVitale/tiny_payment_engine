package com.tpe.paymentorchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.contracts.CreditTransferNotSent;
import com.tpe.contracts.CreditTransferRejected;
import com.tpe.contracts.CreditTransferSettled;
import com.tpe.contracts.Direction;
import com.tpe.contracts.SettlementSource;
import com.tpe.messaging.MessageHeaders;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentorchestrator.SagaProbe.Row;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A payment to another PSP: {@code SendCreditTransfer} out, the CSM's outcome in (specs/11-payment-orchestrator.md
 * §6.2, §6.5, UC-04 5–8).
 */
class OutgoingCsmOutcomeIT extends OrchestratorStack {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration AWAIT = Duration.ofSeconds(15);
    /** Long enough for the listener, the runner and the sweeper to have acted many times. */
    private static final Duration QUIET = Duration.ofSeconds(1);
    /** {@code journalEntryId} of {@code settle-hold-settled.json}. */
    private static final String ENTRY = "0192f0e4-7b1c-7cc3-a1b2-0000000000c1";

    @Autowired
    JdbcClient jdbc;

    private SagaProbe probe;

    @BeforeEach
    void probe() {
        probe = new SagaProbe(jdbc, CLOCK, RISK, LEDGER);
        riskAnswers("allow");
        ledgerAnswers("create-hold-created", "settle-hold-settled", "release-released");
    }

    @Test
    void ORC_AC_02_aPaymentToAnotherPspIsSentAndSettledFromItsHold() {
        UUID paymentId = sent();
        String txId = probe.txId(paymentId);

        assertThat(probe.csmCommands(txId)).singleElement().satisfies(command -> {
            assertThat(command).startsWith("SendCreditTransfer|");
            JsonNode body = JSON.readTree(command.substring(command.indexOf('|') + 1));
            Instant acceptedAt = Instant.parse(body.get("acceptedAt").asString());
            assertThat(Instant.parse(body.get("sendBy").asString())).isEqualTo(acceptedAt.plusSeconds(10));
            assertThat(Instant.parse(body.get("deadlineAt").asString())).isEqualTo(acceptedAt.plusSeconds(20));
            assertThat(body.get("paymentId").asString()).isEqualTo(paymentId.toString());
        });
        assertThat(jdbc.sql("""
                SELECT headers::text FROM outbox WHERE topic = 'csm.outbound.commands' AND message_key = ?""")
                .param(txId).query(String.class).single()).as("the payment's trace (ADR-014)").contains("traceparent");

        publishCsmEvent(settled(paymentId), txId, UUID.randomUUID());

        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId)).isEqualTo(
                new Row("SETTLED", null, null, true)));
        assertThat(probe.transitions(paymentId)).containsExactly("-|RECEIVED|SUBMITTED",
                "RECEIVED|RISK_CHECKED|RISK_ALLOW", "RISK_CHECKED|FUNDS_RESERVED|HOLD_CREATED",
                "FUNDS_RESERVED|SENT_TO_CSM|SENT", "SENT_TO_CSM|SETTLED|HOLD_SETTLED");
        assertThat(probe.finalEvents(paymentId)).containsExactly("PaymentSettled|");
        assertThat(JSON.readTree(finalEvent(paymentId)).get("bookingEntryId").asString()).isEqualTo(ENTRY);
        assertThat(probe.settleCalls(paymentId)).singleElement().satisfies(call ->
                assertThat(call.getHeader("Idempotency-Key")).isEqualTo("hold-settle:" + paymentId));
        assertThat(probe.timers(paymentId)).as("recording the outcome cancels the timers")
                .containsExactly("OUTGOING_DEADLINE|CANCELLED");
        assertThat(jdbc.sql("SELECT csm_outcome FROM payments WHERE payment_id = ?").param(paymentId)
                .query(String.class).single()).isEqualTo("SETTLED");
    }

    @Test
    void ORC_AC_07_notSentRejectsAb03AndReleasesTheHold() {
        UUID paymentId = sent();

        publishCsmEvent(notSent(paymentId), probe.txId(paymentId), UUID.randomUUID());

        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId)).isEqualTo(
                new Row("REJECTED", "AB03", null, true)));
        assertThat(probe.releaseCalls(paymentId)).hasSize(1);
        assertThat(probe.finalEvents(paymentId)).containsExactly("PaymentRejected|AB03");
        assertThat(probe.settleCalls(paymentId)).isEmpty();
    }

    @Test
    void ORC_AC_08_aCsmRejectionReleasesTheHoldWithItsReason() {
        UUID paymentId = sent();

        publishCsmEvent(rejected(paymentId, ReasonCode.AC04), probe.txId(paymentId), UUID.randomUUID());

        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId)).isEqualTo(
                new Row("REJECTED", "AC04", null, true)));
        assertThat(probe.transitions(paymentId)).endsWith("SENT_TO_CSM|REJECTED|CSM_REJECTED");
        assertThat(probe.releaseCalls(paymentId)).hasSize(1);
        assertThat(probe.finalEvents(paymentId)).containsExactly("PaymentRejected|AC04");
    }

    @Test
    void ORC_AC_10_aSettlementAfterTheRejectionOpensACaseAndCallsNoLedger() {
        UUID paymentId = sent();
        String txId = probe.txId(paymentId);
        publishCsmEvent(rejected(paymentId, ReasonCode.AC04), txId, UUID.randomUUID());
        await().atMost(AWAIT).until(() -> probe.row(paymentId).finalEventEmitted());

        publishCsmEvent(settled(paymentId), txId, UUID.randomUUID());

        await().atMost(AWAIT).until(() -> !probe.cases(paymentId).isEmpty());
        assertThat(probe.cases(paymentId)).containsExactly("CONTRADICTING_CSM_OUTCOME|OPEN");
        assertThat(probe.row(paymentId)).isEqualTo(new Row("REJECTED", "AC04", null, true));
        assertThat(probe.settleCalls(paymentId)).isEmpty();
        assertThat(probe.finalEvents(paymentId)).containsExactly("PaymentRejected|AC04");
    }

    @Test
    void ORC_AC_18_aDuplicateEventUnderANewEventIdHasNoEffect() {
        UUID paymentId = sent();
        String txId = probe.txId(paymentId);
        publishCsmEvent(settled(paymentId), txId, UUID.randomUUID());
        await().atMost(AWAIT).until(() -> probe.row(paymentId).finalEventEmitted());

        publishCsmEvent(settled(paymentId), txId, UUID.randomUUID());
        publishCsmEvent(rejected(paymentId, ReasonCode.AC04), txId, UUID.randomUUID());
        publishCsmEvent(rejected(paymentId, ReasonCode.AC04), txId, UUID.randomUUID());

        await().atMost(AWAIT).until(() -> !probe.cases(paymentId).isEmpty());
        await().during(QUIET).atMost(AWAIT).until(() -> probe.cases(paymentId).size() == 1);
        assertThat(probe.settleCalls(paymentId)).hasSize(1);
        assertThat(probe.finalEvents(paymentId)).containsExactly("PaymentSettled|");
        assertThat(jdbc.sql("SELECT count(*) FROM inbox WHERE topic = 'csm.inbound.events' AND dedup_key LIKE ?")
                .param("%:" + txId + ":%").query(Integer.class).single())
                .as("the settlement once, the rejection once").isEqualTo(2);
    }

    @Test
    void aHoldReleasedUnderTheSettlementOpensACaseWithoutAFinalEvent() {
        ledgerAnswers("create-hold-created", "settle-hold-released");
        UUID paymentId = sent();

        publishCsmEvent(settled(paymentId), probe.txId(paymentId), UUID.randomUUID());

        await().atMost(AWAIT).until(() -> !probe.cases(paymentId).isEmpty());
        assertThat(probe.cases(paymentId)).containsExactly("CONTRADICTING_CSM_OUTCOME|OPEN");
        assertThat(probe.row(paymentId)).isEqualTo(new Row("SENT_TO_CSM", null, null, false));
        assertThat(probe.finalEvents(paymentId)).isEmpty();

        // the CSM settled: a late rejection is no outcome of its own
        publishCsmEvent(rejected(paymentId, ReasonCode.AB05), probe.txId(paymentId), UUID.randomUUID());
        await().during(QUIET).atMost(AWAIT).until(() -> probe.row(paymentId).equals(
                new Row("SENT_TO_CSM", null, null, false)));
        assertThat(probe.releaseCalls(paymentId)).isEmpty();
        assertThat(probe.finalEvents(paymentId)).isEmpty();
    }

    @Test
    void anIncomingOutcomeGoesToTheDltUntilTheIncomingFlowExists() {
        String txId = "TPE01INCOMIN" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        publishCsmEvent(new CreditTransferSettled(new TxId(txId), new Bic("PEERDEFFXXX"), Direction.INCOMING, null,
                CLOCK.instant(), SettlementSource.STATUS_REPORT), txId, UUID.randomUUID());

        await().atMost(AWAIT).until(() -> probe.casesWithoutPayment().stream().anyMatch(case_ -> case_.contains(txId)));
        assertThat(probe.casesWithoutPayment()).filteredOn(details -> details.contains(txId)).singleElement()
                .satisfies(details -> assertThat(details).contains("\"cause\": \"UnknownMessageTypeException\""));
    }

    @Test
    void anEventForNoPaymentOfOursOpensACaseWithoutAPaymentAndIsAcknowledged() {
        String txId = "TPE01UNKNOWN" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        publishCsmEvent(new CreditTransferSettled(new TxId(txId), new Bic("TPEBDEFFXXX"), Direction.OUTGOING,
                new PaymentId(UUID.randomUUID()), CLOCK.instant(), SettlementSource.STATUS_REPORT), txId,
                UUID.randomUUID());

        await().atMost(AWAIT).until(() -> probe.casesWithoutPayment().stream().anyMatch(case_ -> case_.contains(txId)));
        assertThat(probe.casesWithoutPayment()).filteredOn(details -> details.contains(txId)).singleElement()
                .satisfies(details -> assertThat(details).contains("\"cause\": \"UNKNOWN_TX\"",
                        "\"type\": \"CreditTransferSettled\""));
    }

    @Test
    void anInvalidEventGoesToTheDltAndOpensADltMessageCaseWithoutAPayment() {
        String key = "TPE01INVALID" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        publishInvalid(key);

        await().atMost(AWAIT).until(() -> probe.casesWithoutPayment().stream().anyMatch(case_ -> case_.contains(key)));
        assertThat(probe.casesWithoutPayment()).filteredOn(details -> details.contains(key)).singleElement()
                .satisfies(details -> assertThat(details).contains("\"topic\": \"csm.inbound.events\""));
    }

    @Test
    void anInvalidEventForAPaymentOpensItsDltMessageCase() {
        UUID paymentId = sent();

        publishInvalid(probe.txId(paymentId));

        await().atMost(AWAIT).until(() -> !probe.cases(paymentId).isEmpty());
        assertThat(probe.cases(paymentId)).containsExactly("DLT_MESSAGE|OPEN");
        assertThat(probe.row(paymentId).state()).isEqualTo("SENT_TO_CSM");
    }

    private static void publishInvalid(String key) {
        publish("csm.inbound.events", key, "{\"not\": \"an event\"}", Map.of(
                MessageHeaders.EVENT_ID, UUID.randomUUID().toString(),
                MessageHeaders.MESSAGE_TYPE, "CreditTransferSettled", MessageHeaders.SCHEMA_VERSION, "1"));
    }

    /** A payment to the peer bank, {@code SENT_TO_CSM}. */
    private UUID sent() {
        Map<String, Object> request = PaymentCommandIT.request(Ibans.own(), Ibans.peer());
        String body = http().post().uri("/v1/payments").contentType(MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsString(request)).retrieve().body(String.class);
        UUID paymentId = UUID.fromString(JSON.readTree(body).get("paymentId").asString());
        await().atMost(AWAIT).until(() -> "SENT_TO_CSM".equals(probe.row(paymentId).state()));
        return paymentId;
    }

    private CreditTransferSettled settled(UUID paymentId) {
        return new CreditTransferSettled(new TxId(probe.txId(paymentId)), debtorAgent(paymentId), Direction.OUTGOING,
                new PaymentId(paymentId), CLOCK.instant(), SettlementSource.STATUS_REPORT);
    }

    private CreditTransferRejected rejected(UUID paymentId, ReasonCode reason) {
        return new CreditTransferRejected(new TxId(probe.txId(paymentId)), debtorAgent(paymentId),
                Direction.OUTGOING, new PaymentId(paymentId), reason, CLOCK.instant(), SettlementSource.STATUS_REPORT);
    }

    private CreditTransferNotSent notSent(UUID paymentId) {
        Instant sendBy = jdbc.sql("SELECT send_by FROM payments WHERE payment_id = ?").param(paymentId)
                .query(java.sql.Timestamp.class).single().toInstant();
        return new CreditTransferNotSent(new TxId(probe.txId(paymentId)), debtorAgent(paymentId), Direction.OUTGOING,
                new PaymentId(paymentId), ReasonCode.AB03, sendBy, CLOCK.instant());
    }

    private Bic debtorAgent(UUID paymentId) {
        return new Bic(jdbc.sql("SELECT debtor_agent_bic FROM payments WHERE payment_id = ?").param(paymentId)
                .query(String.class).single());
    }

    private String finalEvent(UUID paymentId) {
        return jdbc.sql("SELECT payload::text FROM outbox WHERE topic = 'payments.events' AND message_key = ?")
                .param(paymentId.toString()).query(String.class).single();
    }
}
