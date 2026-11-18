package com.tpe.csmgateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.tpe.csmgateway.IncomingPacs008.OTHER_BIC;
import static com.tpe.csmgateway.IncomingPacs008.PEER_BIC;
import static com.tpe.csmgateway.IncomingPacs008.pacs008;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.contracts.AcceptCreditTransfer;
import com.tpe.contracts.RejectCreditTransfer;
import com.tpe.iso20022model.CsmMessageType;
import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.EnvelopeCodec.Accepted;
import com.tpe.iso20022model.pacs002v10.PaymentTransaction110;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.TxId;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The answer coordinator: exactly one answer per incoming transaction (specs/15-csm-gateway.md §6.3, UC-06 6, 6b). */
class AnswerCoordinatorIT extends GatewayStack {

    private static final Duration HANDLED = Duration.ofSeconds(15);
    private static final Duration QUIET = Duration.ofSeconds(1);
    private static final Instant DEADLINE = START.plusSeconds(5);

    private final EnvelopeCodec envelopes = new EnvelopeCodec();

    @Autowired
    private MeterRegistry meters;

    @Test
    void GW_AC_11_aVerdictBeforeTheDeadlineIsTheAnswerAndOneAfterItIsIgnoredForTheDeadlinesRejection() {
        received("TX-ON-TIME", PEER_BIC);
        publishCommand("TX-ON-TIME", accept("TX-ON-TIME", PEER_BIC), UUID.randomUUID());

        await().atMost(HANDLED).until(() -> sentToCsm().size() == 1);
        PaymentTransaction110 accepted = statusReport(sentToCsm().getFirst());
        assertThat(accepted.getTxSts()).isEqualTo("ACCP");
        assertThat(accepted.getStsRsnInf()).isEmpty();
        assertThat(accepted.getOrgnlTxId()).isEqualTo("TX-ON-TIME");
        assertThat(accepted.getOrgnlEndToEndId()).as("read from the journalled pacs.008").isEqualTo("E2E-TX-ON-TIME");
        assertThat(accepted.getOrgnlUETR()).isEqualTo(IncomingPacs008.uetr("TX-ON-TIME").value());
        assertThat(accepted.getOrgnlTxRef().getDbtrAgt().getFinInstnId().getBICFI()).isEqualTo(PEER_BIC.value());
        assertThat(incoming(PEER_BIC, "TX-ON-TIME")).containsEntry("answer_status", "ACCP")
                .containsEntry("answer_decided_by", "VERDICT").containsEntry("answered_at", Timestamp.from(START));
        assertThat(events("TX-ON-TIME")).extracting(Event::type)
                .containsExactly("CreditTransferReceived", "AnswerSent");
        assertThat(events("TX-ON-TIME").get(1).payload()).contains("\"status\": \"ACCP\"",
                "\"decidedBy\": \"VERDICT\"", "\"direction\": \"INCOMING\"", "\"debtorAgentBic\": \"PEERDEFFXXX\"",
                "\"sentAt\": \"2026-11-15T10:00:00.000Z\"").doesNotContain("reasonCode");

        double ignoredBefore = ignored();
        received("TX-LATE", PEER_BIC);
        CLOCK.set(DEADLINE);
        await().atMost(HANDLED).until(() -> "DEADLINE".equals(incoming(PEER_BIC, "TX-LATE").get("answer_decided_by")));
        publishCommand("TX-LATE", accept("TX-LATE", PEER_BIC), UUID.randomUUID());

        await().atMost(HANDLED).until(() -> notes("TX-LATE").contains("verdict ignored"));
        assertThat(incoming(PEER_BIC, "TX-LATE")).containsEntry("answer_status", "RJCT")
                .containsEntry("answer_reason", "AB05");
        assertThat(events("TX-LATE")).extracting(Event::type).containsExactly("CreditTransferReceived", "AnswerSent");
        assertThat(events("TX-LATE").get(1).payload()).contains("\"status\": \"RJCT\"", "\"reasonCode\": \"AB05\"",
                "\"decidedBy\": \"DEADLINE\"");
        await().atMost(HANDLED).until(() -> sentToCsm().size() == 2);
        PaymentTransaction110 deadline = statusReport(sentToCsm().get(1));
        assertThat(deadline.getTxSts()).isEqualTo("RJCT");
        assertThat(deadline.getStsRsnInf().getFirst().getRsn().getCd()).isEqualTo("AB05");
        assertThat(ignored()).isEqualTo(ignoredBefore + 1);
    }

    @Test
    void aRejectingVerdictIsAnsweredWithItsReason() {
        received("TX-REJECT", PEER_BIC);

        publishCommand("TX-REJECT", reject("TX-REJECT", PEER_BIC), UUID.randomUUID());

        await().atMost(HANDLED).until(() -> sentToCsm().size() == 1);
        PaymentTransaction110 rejected = statusReport(sentToCsm().getFirst());
        assertThat(rejected.getTxSts()).isEqualTo("RJCT");
        assertThat(rejected.getStsRsnInf().getFirst().getRsn().getCd()).isEqualTo("AC06");
        assertThat(events("TX-REJECT").getLast().payload()).contains("\"status\": \"RJCT\"",
                "\"reasonCode\": \"AC06\"", "\"decidedBy\": \"VERDICT\"");
    }

    @Test
    void GW_AC_15_verdictsForTheSameTxIdFromTwoDebtorAgentsAnswerEachItsOwnTransfer() {
        received("TX-SHARED", PEER_BIC);
        received("TX-SHARED", OTHER_BIC);

        publishCommand("TX-SHARED", accept("TX-SHARED", PEER_BIC), UUID.randomUUID());
        publishCommand("TX-SHARED", reject("TX-SHARED", OTHER_BIC), UUID.randomUUID());

        await().atMost(HANDLED).until(() -> sentToCsm().size() == 2);
        assertThat(sentToCsm()).extracting(this::statusReport)
                .extracting(t -> t.getOrgnlTxRef().getDbtrAgt().getFinInstnId().getBICFI(), t -> t.getTxSts())
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(PEER_BIC.value(), "ACCP"),
                        org.assertj.core.groups.Tuple.tuple(OTHER_BIC.value(), "RJCT"));
        assertThat(incoming(PEER_BIC, "TX-SHARED")).containsEntry("answer_status", "ACCP");
        assertThat(incoming(OTHER_BIC, "TX-SHARED")).containsEntry("answer_status", "RJCT");
        assertThat(events("TX-SHARED")).extracting(Event::type).containsExactlyInAnyOrder("CreditTransferReceived",
                "CreditTransferReceived", "AnswerSent", "AnswerSent");
        assertThat(notes("TX-SHARED")).doesNotContain("verdict ignored");
    }

    @Test
    void GW_AC_16_anAnswerTheCsmNeverTakesIsRetriedUntilTheWindowClosesAndItsAnswerSentStands() {
        CSM.stubFor(post("/csm/v1/messages").willReturn(aResponse().withStatus(503)));
        received("TX-NO-CSM", PEER_BIC);
        publishCommand("TX-NO-CSM", accept("TX-NO-CSM", PEER_BIC), UUID.randomUUID());
        // the first attempt failed and its retry is set, before the clock moves
        await().atMost(HANDLED).until(() -> Timestamp.from(START.plusMillis(200))
                .equals(incoming(PEER_BIC, "TX-NO-CSM").get("next_transmit_at")));

        CLOCK.set(DEADLINE.plusMillis(999));
        await().atMost(HANDLED).until(() -> sentToCsm().size() == 2);
        CLOCK.set(DEADLINE.plusSeconds(2));

        await().atMost(HANDLED).until(() -> "GAVE_UP".equals(incoming(PEER_BIC, "TX-NO-CSM").get("transmit_status")));
        CLOCK.set(DEADLINE.plusSeconds(60));
        await().during(QUIET).atMost(HANDLED).until(() -> sentToCsm().size() == 2);
        assertThat(sentToCsm().get(1)).as("the same answer_msg_id").isEqualTo(sentToCsm().getFirst());
        assertThat(incoming(PEER_BIC, "TX-NO-CSM")).containsEntry("answer_decided_by", "VERDICT");
        assertThat(events("TX-NO-CSM")).extracting(Event::type).containsExactly("CreditTransferReceived", "AnswerSent");
    }

    @Test
    void aRedeliveredVerdictHasNoEffectAndASecondDifferentOneIsIgnored() {
        received("TX-TWICE", PEER_BIC);
        UUID eventId = UUID.randomUUID();
        publishCommand("TX-TWICE", accept("TX-TWICE", PEER_BIC), eventId);
        await().atMost(HANDLED).until(() -> sentToCsm().size() == 1);

        publishCommand("TX-TWICE", accept("TX-TWICE", PEER_BIC), eventId);
        publishCommand("TX-TWICE", reject("TX-TWICE", PEER_BIC), UUID.randomUUID());

        await().atMost(HANDLED).until(() -> notes("TX-TWICE").contains("verdict ignored"));
        await().during(QUIET).atMost(HANDLED).until(() -> sentToCsm().size() == 1);
        assertThat(notes("TX-TWICE")).filteredOn("verdict ignored"::equals).as("the redelivery is not journalled")
                .hasSize(1);
        assertThat(incoming(PEER_BIC, "TX-TWICE")).containsEntry("answer_status", "ACCP");
        assertThat(events("TX-TWICE")).extracting(Event::type).containsExactly("CreditTransferReceived", "AnswerSent");
    }

    @Test
    void aVerdictForNoKnownTransferIsIgnored() {
        publishCommand("TX-NONE", accept("TX-NONE", PEER_BIC), UUID.randomUUID());

        await().atMost(HANDLED).until(() -> notes("TX-NONE").contains("verdict ignored"));
        assertThat(events("TX-NONE")).isEmpty();
        assertThat(sentToCsm()).isEmpty();
    }

    @Test
    void theAnswerDeadlineFiresWithin100msOfItP99() throws Exception {
        int transfers = 100;
        long[] latencies = new long[transfers];
        Instant at = START;
        for (int i = 0; i < transfers; i++) {
            String txId = "TX-DEADLINE-" + i;
            CLOCK.set(at);
            received(txId, PEER_BIC);
            CLOCK.set(at.plusSeconds(5));
            long started = System.nanoTime();
            while (incoming(PEER_BIC, txId).get("answer_status") == null) {
                assertThat(System.nanoTime() - started).as("answered within %s", HANDLED)
                        .isLessThan(HANDLED.toNanos());
                Thread.sleep(1);
            }
            latencies[i] = System.nanoTime() - started;
            at = at.plusSeconds(10);
        }

        long[] sorted = latencies.clone();
        Arrays.sort(sorted);
        long p99 = sorted[(int) Math.ceil(sorted.length * 0.99) - 1];
        assertThat(Duration.ofNanos(p99)).as("p99 of %d answer deadlines (max %d ms)", transfers,
                TimeUnit.NANOSECONDS.toMillis(sorted[sorted.length - 1])).isLessThanOrEqualTo(Duration.ofMillis(100));
    }

    /** A valid incoming transfer, received now. */
    private void received(String txId, Bic debtorAgent) {
        assertThat(inbound(pacs008("M-" + debtorAgent.value() + "-" + txId, txId, debtorAgent, CLOCK.instant()))
                .getStatusCode().value()).isEqualTo(202);
    }

    private static AcceptCreditTransfer accept(String txId, Bic debtorAgent) {
        return new AcceptCreditTransfer(new PaymentId(UUID.randomUUID()), new TxId(txId), debtorAgent, START);
    }

    private static RejectCreditTransfer reject(String txId, Bic debtorAgent) {
        return new RejectCreditTransfer(new PaymentId(UUID.randomUUID()), new TxId(txId), debtorAgent, START,
                ReasonCode.AC06);
    }

    private PaymentTransaction110 statusReport(String xml) {
        var envelope = ((Accepted) envelopes.split(xml)).envelope();
        assertThat(envelope.messageType()).isEqualTo(CsmMessageType.PACS_002);
        return ((com.tpe.iso20022model.pacs002v10.Document) envelope.document()).getFIToFIPmtStsRpt()
                .getTxInfAndSts().getFirst();
    }

    private Map<String, Object> incoming(Bic debtorAgent, String txId) {
        return jdbc.sql("SELECT * FROM incoming_transfers WHERE debtor_agent_bic = :bic AND tx_id = :txId")
                .param("bic", debtorAgent.value()).param("txId", txId).query().listOfRows().stream().findFirst()
                .orElse(Map.of());
    }

    private List<String> notes(String txId) {
        return jdbc.sql("SELECT note FROM message_journal WHERE tx_id = :txId ORDER BY id").param("txId", txId)
                .query((rs, n) -> rs.getString("note")).list();
    }

    private double ignored() {
        var counter = meters.find("tpe.csm.verdict.ignored").counter();
        return counter == null ? 0 : counter.count();
    }
}
