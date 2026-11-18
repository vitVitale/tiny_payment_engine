package com.tpe.csmgateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.tpe.csmgateway.Commands.OWN_BIC;
import static com.tpe.csmgateway.Commands.requestPaymentStatus;
import static com.tpe.csmgateway.Commands.sendCreditTransfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.contracts.SendCreditTransfer;
import com.tpe.iso20022model.CsmEnvelope;
import com.tpe.iso20022model.CsmMessageType;
import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.EnvelopeCodec.Accepted;
import com.tpe.iso20022model.Pacs002Builder;
import com.tpe.iso20022model.Pacs002Builder.Status;
import com.tpe.iso20022model.Pacs002Builder.StatusReport;
import com.tpe.iso20022model.pacs028v03.PaymentTransaction113;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.MsgId;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** {@code RequestPaymentStatus(OUTGOING)} and the answer to its {@code pacs.028} (specs/15-csm-gateway.md §6.4, §6.6). */
class RequestPaymentStatusIT extends GatewayStack {

    private static final Duration HANDLED = Duration.ofSeconds(15);
    /** Long enough for the listener and the transmit worker to have acted many times. */
    private static final Duration QUIET = Duration.ofSeconds(1);
    private static final Bic CSM_BIC = new Bic("TIPSXXXXXXX");
    private static final AtomicInteger REPORTS = new AtomicInteger();

    private final EnvelopeCodec envelopes = new EnvelopeCodec();

    @Test
    void GW_AC_05_aNotSentTransferIsAnsweredNotSentAgainWithoutAPacs028() {
        // sendBy = START - 10 s: passed when the command arrives
        SendCreditTransfer command = sendCreditTransfer("TX-AC05", START.minusSeconds(20));
        publishCommand("TX-AC05", command, UUID.randomUUID());
        await().atMost(HANDLED).until(() -> events("TX-AC05").size() == 1);

        publishCommand("TX-AC05", requestPaymentStatus(command, 1), UUID.randomUUID());

        await().atMost(HANDLED).until(() -> events("TX-AC05").size() == 2);
        assertThat(events("TX-AC05")).extracting(Event::payload).containsOnly(events("TX-AC05").getFirst().payload());
        assertThat(events("TX-AC05").get(1).type()).isEqualTo("CreditTransferNotSent");
        await().during(QUIET).atMost(HANDLED).until(() -> sentToCsm().isEmpty());
        assertThat(statusRequests("TX-AC05")).isEmpty();
    }

    @Test
    void aTransferThatNeverArrivedIsAnsweredNotSentAb03AndIsNeverSentAfter() {
        SendCreditTransfer command = sendCreditTransfer("TX-NONE", START.minusSeconds(1));

        publishCommand("TX-NONE", requestPaymentStatus(command, 1), UUID.randomUUID());

        await().atMost(HANDLED).until(() -> !events("TX-NONE").isEmpty());
        assertThat(events("TX-NONE")).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("CreditTransferNotSent");
            assertThat(event.payload()).contains("\"reasonCode\": \"AB03\"", "\"direction\": \"OUTGOING\"",
                    "\"paymentId\": \"" + command.paymentId().value() + "\"");
        });

        publishCommand("TX-NONE", command, UUID.randomUUID());
        await().during(QUIET).atMost(HANDLED).until(() -> sentToCsm().isEmpty());
        assertThat(transfer("TX-NONE")).as("a SendCreditTransfer after the NotSent is a duplicate").isEmpty();
        assertThat(events("TX-NONE")).hasSize(1);
    }

    @Test
    void aTransferWithAFinalStatusRepublishesItsFinalEvent() {
        SendCreditTransfer command = sent("TX-FINAL");
        inbound(answer(command, (String) transfer("TX-FINAL").get("msg_id"), CsmMessageType.PACS_008, Status.SETTLED));

        publishCommand("TX-FINAL", requestPaymentStatus(command, 1), UUID.randomUUID());

        await().atMost(HANDLED).until(() -> events("TX-FINAL").size() == 2);
        assertThat(events("TX-FINAL")).extracting(Event::type)
                .containsExactly("CreditTransferSettled", "CreditTransferSettled");
        assertThat(events("TX-FINAL").get(1).payload()).as("as first published")
                .isEqualTo(events("TX-FINAL").getFirst().payload());
        await().during(QUIET).atMost(HANDLED).until(() -> sentToCsm().size() == 1);
    }

    @Test
    void GW_AC_06_aSentTransferIsInvestigatedAndTheSettledAnswerIsPublishedAsInvestigation() {
        SendCreditTransfer command = sent("TX-AC06");
        String pacs008MsgId = (String) transfer("TX-AC06").get("msg_id");

        publishCommand("TX-AC06", requestPaymentStatus(command, 1), UUID.randomUUID());

        await().atMost(HANDLED).until(() -> sentToCsm().size() == 2);
        CsmEnvelope request = ((Accepted) envelopes.split(sentToCsm().get(1))).envelope();
        assertThat(request.messageType()).isEqualTo(CsmMessageType.PACS_028);
        var statusRequest = ((com.tpe.iso20022model.pacs028v03.Document) request.document()).getFIToFIPmtStsReq();
        PaymentTransaction113 asked = statusRequest.getTxInf().getFirst();
        assertThat(asked.getOrgnlTxId()).isEqualTo("TX-AC06");
        assertThat(asked.getOrgnlGrpInf().getOrgnlMsgId()).isEqualTo(pacs008MsgId);
        assertThat(asked.getOrgnlEndToEndId()).isEqualTo(command.endToEndId().value());
        assertThat(asked.getOrgnlUETR()).isEqualTo(command.uetr().value());
        assertThat(asked.getOrgnlTxRef().getDbtrAgt().getFinInstnId().getBICFI()).isEqualTo(OWN_BIC.value());
        assertThat(asked.getInstdAgt().getFinInstnId().getBICFI()).isEqualTo(CSM_BIC.value());
        String requestMsgId = statusRequest.getGrpHdr().getMsgId();
        await().atMost(HANDLED).until(() -> "DELIVERED".equals(statusRequests("TX-AC06").getFirst()
                .get("transmit_status")));
        assertThat(statusRequests("TX-AC06")).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("debtor_agent_bic", OWN_BIC.value()).containsEntry("direction", "OUTGOING")
                .containsEntry("attempt", 1).containsEntry("msg_id", requestMsgId));
        assertThat(jdbc.sql("SELECT message_type FROM message_journal WHERE msg_id = :msgId AND direction = 'OUT'")
                .param("msgId", requestMsgId).query(String.class).single()).isEqualTo("pacs.028.001.03");

        inbound(answer(command, requestMsgId, CsmMessageType.PACS_028, Status.SETTLED));

        assertThat(transfer("TX-AC06")).containsEntry("final_status", "SETTLED");
        assertThat(events("TX-AC06")).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("CreditTransferSettled");
            assertThat(event.payload()).contains("\"source\": \"INVESTIGATION\"", "\"direction\": \"OUTGOING\"",
                    "\"paymentId\": \"" + command.paymentId().value() + "\"");
        });
    }

    @Test
    void GW_AC_07_unknownTxAfterTheDeadlineIsRejectedAb05() {
        SendCreditTransfer command = sent("TX-AC07");
        String requestMsgId = investigated(command, 1);
        CLOCK.set(command.deadlineAt());

        inbound(answer(command, requestMsgId, CsmMessageType.PACS_028, Status.UNKNOWN_TRANSACTION));

        assertThat(transfer("TX-AC07")).containsEntry("final_status", "REJECTED").containsEntry("final_reason", "AB05");
        assertThat(events("TX-AC07")).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("CreditTransferRejected");
            assertThat(event.payload()).contains("\"reasonCode\": \"AB05\"", "\"source\": \"INVESTIGATION\"");
        });
    }

    @Test
    void GW_AC_07_unknownTxBeforeTheDeadlineIsJournalledOnly() {
        SendCreditTransfer command = sent("TX-AC07B");
        String requestMsgId = investigated(command, 1);
        CLOCK.set(command.deadlineAt().minusMillis(1));

        inbound(answer(command, requestMsgId, CsmMessageType.PACS_028, Status.UNKNOWN_TRANSACTION));

        assertThat(transfer("TX-AC07B")).containsEntry("final_status", null);
        assertThat(events("TX-AC07B")).isEmpty();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM message_journal
                 WHERE tx_id = 'TX-AC07B' AND direction = 'IN' AND message_type = 'pacs.002.001.10'""")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void unknownTxThatAnswersNoStatusRequestIsJournalledOnly() {
        SendCreditTransfer command = sent("TX-UNASKED");
        CLOCK.set(command.deadlineAt().plusSeconds(1));

        inbound(answer(command, (String) transfer("TX-UNASKED").get("msg_id"), CsmMessageType.PACS_008,
                Status.UNKNOWN_TRANSACTION));

        assertThat(transfer("TX-UNASKED")).containsEntry("final_status", null);
        assertThat(events("TX-UNASKED")).isEmpty();
    }

    @Test
    void aRedeliveredRequestSendsNoSecondPacs028AndTheNextAttemptSendsANewOne() {
        SendCreditTransfer command = sent("TX-REDO");
        String first = investigated(command, 1);

        publishCommand("TX-REDO", requestPaymentStatus(command, 1), UUID.randomUUID());
        await().during(QUIET).atMost(HANDLED).until(() -> sentToCsm().size() == 2);

        publishCommand("TX-REDO", requestPaymentStatus(command, 2), UUID.randomUUID());
        await().atMost(HANDLED).until(() -> sentToCsm().size() == 3);
        await().atMost(HANDLED).until(() -> statusRequests("TX-REDO").size() == 2);
        assertThat(statusRequests("TX-REDO")).extracting(row -> row.get("attempt")).containsExactly(1, 2);
        assertThat(statusRequests("TX-REDO").get(1).get("msg_id")).isNotEqualTo(first);
    }

    @Test
    void aStatusRequestIsGivenUpAfterFiveAttempts() {
        SendCreditTransfer command = sent("TX-GIVEUP");
        CSM.stubFor(post("/csm/v1/messages").willReturn(aResponse().withStatus(503)));

        publishCommand("TX-GIVEUP", requestPaymentStatus(command, 1), UUID.randomUUID());
        await().atMost(HANDLED).until(() -> sentToCsm().size() == 2);
        retrySet("TX-GIVEUP", 1);
        // back-off 200, 400, 800, 1000 ms: one more attempt per second of the service's time
        for (int second = 1; second <= 4; second++) {
            int sends = second + 2;
            CLOCK.set(START.plusSeconds(second));
            await().atMost(HANDLED).until(() -> sentToCsm().size() == sends);
            retrySet("TX-GIVEUP", second + 1);
        }
        CLOCK.set(START.plusSeconds(5));

        await().atMost(HANDLED).until(() -> "GAVE_UP".equals(statusRequests("TX-GIVEUP").getFirst()
                .get("transmit_status")));
        CLOCK.set(START.plusSeconds(60));
        await().during(QUIET).atMost(HANDLED).until(() -> sentToCsm().size() == 6);
        assertThat(statusRequests("TX-GIVEUP").getFirst()).containsEntry("transmit_attempts", 5);
        assertThat(events("TX-GIVEUP")).isEmpty();
    }

    /** A transfer the gateway sent and the CSM took. */
    private SendCreditTransfer sent(String txId) {
        SendCreditTransfer command = sendCreditTransfer(txId, START.minusSeconds(1));
        publishCommand(txId, command, UUID.randomUUID());
        await().atMost(HANDLED).until(() -> "DELIVERED".equals(transfer(txId).get("transmit_status")));
        return command;
    }

    /** Requests the status as {@code attempt} and waits for the {@code pacs.028}; returns its {@code MsgId}. */
    private String investigated(SendCreditTransfer command, int attempt) {
        String txId = command.txId().value();
        publishCommand(txId, requestPaymentStatus(command, attempt), UUID.randomUUID());
        await().atMost(HANDLED).until(() -> statusRequests(txId).stream()
                .anyMatch(row -> Integer.valueOf(attempt).equals(row.get("attempt"))
                        && "DELIVERED".equals(row.get("transmit_status"))));
        return (String) statusRequests(txId).stream().filter(row -> Integer.valueOf(attempt).equals(row.get("attempt")))
                .findFirst().orElseThrow().get("msg_id");
    }

    private String answer(SendCreditTransfer command, String originalMsgId, CsmMessageType originalType,
            Status status) {
        MsgId msgId = new MsgId("SIMM-" + REPORTS.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8));
        return envelopes.join(new Pacs002Builder().build(new StatusReport(msgId, CLOCK.instant(),
                new MsgId(originalMsgId), originalType, command.txId(), command.endToEndId(), command.uetr(), OWN_BIC,
                status, CSM_BIC, OWN_BIC)));
    }

    /**
     * Waits until the failed attempt {@code attempt} has set its retry: the CSM saw the request before the transmitter
     * read the clock to back off, so a clock moved before then would push the retry past where the test expects it.
     * A retry is at most 1 s ahead, a claim's lease 2 s.
     */
    private void retrySet(String txId, int attempt) {
        await().atMost(HANDLED).until(() -> {
            Map<String, Object> request = statusRequests(txId).getFirst();
            return Integer.valueOf(attempt).equals(request.get("transmit_attempts"))
                    && request.get("next_transmit_at") instanceof Timestamp next
                    && next.toInstant().isBefore(CLOCK.instant().plusMillis(1_500));
        });
    }

    private List<Map<String, Object>> statusRequests(String txId) {
        return jdbc.sql("SELECT * FROM status_requests WHERE tx_id = :txId ORDER BY attempt").param("txId", txId)
                .query().listOfRows();
    }
}
