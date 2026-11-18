package com.tpe.csmgateway;

import static com.tpe.csmgateway.IncomingPacs008.OTHER_BIC;
import static com.tpe.csmgateway.IncomingPacs008.OWN_BIC;
import static com.tpe.csmgateway.IncomingPacs008.PEER_BIC;
import static com.tpe.csmgateway.IncomingPacs008.pacs008;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.awaitility.Awaitility.await;

import com.tpe.contracts.AcceptCreditTransfer;
import com.tpe.contracts.Direction;
import com.tpe.contracts.RequestPaymentStatus;
import com.tpe.iso20022model.CsmMessageType;
import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.EnvelopeCodec.Accepted;
import com.tpe.iso20022model.Pacs002Builder;
import com.tpe.iso20022model.Pacs002Builder.Status;
import com.tpe.iso20022model.Pacs002Builder.StatusReport;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.TxId;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The CSM's final status of an incoming transfer (specs/15-csm-gateway.md §6.4, §6.6, ADR-010, UC-06 8, 8a, 8b): its
 * {@code pacs.002} after our answer, and the answer to our {@code pacs.028} for it.
 */
class IncomingSettlementIT extends GatewayStack {

    private static final Duration HANDLED = Duration.ofSeconds(15);
    private static final Duration QUIET = Duration.ofSeconds(1);
    private static final Bic CSM_BIC = new Bic("TIPSXXXXXXX");
    private static final AtomicInteger REPORTS = new AtomicInteger();

    private final EnvelopeCodec envelopes = new EnvelopeCodec();

    @Test
    void aSettlementConfirmationPublishesCreditTransferSettledAndARejectionCreditTransferRejected() {
        answered("TX-ACSC", PEER_BIC);
        answered("TX-RJCT", PEER_BIC);

        assertThat(inbound(report("TX-ACSC", PEER_BIC, Status.SETTLED)).getStatusCode().value()).isEqualTo(202);
        inbound(report("TX-RJCT", PEER_BIC, Status.rejected(ReasonCode.AB05)));

        assertThat(incoming(PEER_BIC, "TX-ACSC")).containsEntry("final_status", "SETTLED")
                .containsEntry("final_source", "STATUS_REPORT");
        assertThat(finalEvents("TX-ACSC")).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("CreditTransferSettled");
            assertThat(event.payload()).contains("\"direction\": \"INCOMING\"", "\"debtorAgentBic\": \"PEERDEFFXXX\"",
                    "\"source\": \"STATUS_REPORT\"").doesNotContain("paymentId");
        });
        assertThat(incoming(PEER_BIC, "TX-RJCT")).containsEntry("final_status", "REJECTED")
                .containsEntry("final_reason", "AB05");
        assertThat(finalEvents("TX-RJCT")).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("CreditTransferRejected");
            assertThat(event.payload()).contains("\"direction\": \"INCOMING\"", "\"reasonCode\": \"AB05\"");
        });
        assertThat(notes("TX-ACSC")).last().isNull();
    }

    @Test
    void GW_AC_13_aDuplicateReportOfAnIncomingTransferPublishesNothingAndAContradictingOneIsPublishedWhileTheFirstStays() {
        answered("TX-AC13-IN", PEER_BIC);
        inbound(report("TX-AC13-IN", PEER_BIC, Status.SETTLED));

        inbound(report("TX-AC13-IN", PEER_BIC, Status.SETTLED));
        assertThat(finalEvents("TX-AC13-IN")).extracting(Event::type).containsExactly("CreditTransferSettled");

        inbound(report("TX-AC13-IN", PEER_BIC, Status.rejected(ReasonCode.AB05)));
        assertThat(finalEvents("TX-AC13-IN")).extracting(Event::type)
                .containsExactly("CreditTransferSettled", "CreditTransferRejected");
        assertThat(incoming(PEER_BIC, "TX-AC13-IN")).containsEntry("final_status", "SETTLED");
        assertThat(notes("TX-AC13-IN")).endsWith("duplicate status", "contradicting status, first kept");
    }

    @Test
    void aReportWithoutADebtorAgentIsMatchedByItsOrgnlMsgIdOnly() {
        answered("TX-NO-DBTRAGT", PEER_BIC);
        String withoutDebtorAgent = report("TX-NO-DBTRAGT", PEER_BIC, Status.SETTLED)
                .replaceAll("(?s)<OrgnlTxRef>.*?</OrgnlTxRef>", "");

        inbound(withoutDebtorAgent);

        assertThat(incoming(PEER_BIC, "TX-NO-DBTRAGT")).containsEntry("final_status", "SETTLED");
        assertThat(finalEvents("TX-NO-DBTRAGT")).extracting(Event::type).containsExactly("CreditTransferSettled");
    }

    @Test
    void aStatusRequestForAnIncomingTransferWithoutItsPacs008IsJournalledAndSendsNothing() {
        answered("TX-NO-PACS008", PEER_BIC);
        jdbc.sql("DELETE FROM message_journal WHERE tx_id = 'TX-NO-PACS008' AND message_type = 'pacs.008.001.08'")
                .update();
        int sent = sentToCsm().size();

        publishCommand("TX-NO-PACS008", statusRequest("TX-NO-PACS008", PEER_BIC, 1), UUID.randomUUID());

        await().atMost(HANDLED).until(() -> notes("TX-NO-PACS008")
                .contains("status request, no journalled pacs.008"));
        assertThat(sentToCsm()).hasSize(sent);
        assertThat(jdbc.sql("SELECT count(*) FROM status_requests WHERE tx_id = 'TX-NO-PACS008'")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void aReportForTheSameTxIdFromAnotherBankIsUncorrelated() {
        answered("TX-OTHER", PEER_BIC);

        inbound(report("TX-OTHER", OTHER_BIC, Status.SETTLED));

        assertThat(incoming(PEER_BIC, "TX-OTHER")).containsEntry("final_status", null);
        assertThat(finalEvents("TX-OTHER")).isEmpty();
        assertThat(notes("TX-OTHER")).last().isEqualTo("uncorrelated");
    }

    @Test
    void GW_AC_17_statusRequestsForTheSameTxIdFromTwoBanksEachAskAndSettleOrRejectOnlyTheirOwn() {
        answered("TX-SHARED", PEER_BIC);
        answered("TX-SHARED", OTHER_BIC);
        int answers = sentToCsm().size();

        publishCommand("TX-SHARED", statusRequest("TX-SHARED", PEER_BIC, 1), UUID.randomUUID());
        publishCommand("TX-SHARED", statusRequest("TX-SHARED", OTHER_BIC, 1), UUID.randomUUID());

        await().atMost(HANDLED).until(() -> delivered("TX-SHARED").size() == 2);
        assertThat(delivered("TX-SHARED")).extracting(r -> r.get("debtor_agent_bic"), r -> r.get("direction"))
                .containsExactlyInAnyOrder(tuple("PEERDEFFXXX", "INCOMING"), tuple("OTHRDEFFXXX", "INCOMING"));
        List<String> requests = sentToCsm().subList(answers, sentToCsm().size());
        assertThat(requests).hasSize(2).allSatisfy(xml -> assertThat(type(xml)).isEqualTo(CsmMessageType.PACS_028))
                .anySatisfy(xml -> assertThat(xml).contains("<BICFI>PEERDEFFXXX</BICFI>"))
                .anySatisfy(xml -> assertThat(xml).contains("<BICFI>OTHRDEFFXXX</BICFI>"))
                .allSatisfy(xml -> assertThat(xml).contains("<OrgnlEndToEndId>E2E-TX-SHARED</OrgnlEndToEndId>",
                        "<OrgnlTxId>TX-SHARED</OrgnlTxId>"));

        inbound(investigationAnswer("TX-SHARED", PEER_BIC, requestMsgId("TX-SHARED", PEER_BIC), Status.SETTLED));
        inbound(investigationAnswer("TX-SHARED", OTHER_BIC, requestMsgId("TX-SHARED", OTHER_BIC),
                Status.rejected(ReasonCode.AB05)));

        assertThat(incoming(PEER_BIC, "TX-SHARED")).containsEntry("final_status", "SETTLED")
                .containsEntry("final_source", "INVESTIGATION");
        assertThat(incoming(OTHER_BIC, "TX-SHARED")).containsEntry("final_status", "REJECTED")
                .containsEntry("final_reason", "AB05").containsEntry("final_source", "INVESTIGATION");
        assertThat(finalEvents("TX-SHARED")).extracting(Event::type, event -> event.payload().contains("PEERDEFFXXX"))
                .containsExactlyInAnyOrder(tuple("CreditTransferSettled", true), tuple("CreditTransferRejected", false));
        assertThat(finalEvents("TX-SHARED")).allSatisfy(event ->
                assertThat(event.payload()).contains("\"source\": \"INVESTIGATION\""));
    }

    @Test
    void aStatusRequestForAnIncomingTransferWithAFinalStatusRepublishesItAndSendsNoPacs028() {
        answered("TX-KNOWN", PEER_BIC);
        inbound(report("TX-KNOWN", PEER_BIC, Status.SETTLED));
        int sent = sentToCsm().size();

        publishCommand("TX-KNOWN", statusRequest("TX-KNOWN", PEER_BIC, 1), UUID.randomUUID());

        await().atMost(HANDLED).until(() -> finalEvents("TX-KNOWN").size() == 2);
        assertThat(finalEvents("TX-KNOWN")).extracting(Event::type)
                .containsExactly("CreditTransferSettled", "CreditTransferSettled");
        assertThat(finalEvents("TX-KNOWN").get(1).payload()).isEqualTo(finalEvents("TX-KNOWN").get(0).payload());
        await().during(QUIET).atMost(HANDLED).until(() -> sentToCsm().size() == sent);
        assertThat(delivered("TX-KNOWN")).isEmpty();
    }

    @Test
    void aStatusRequestForNoIncomingTransferIsJournalledAndSendsNothing() {
        publishCommand("TX-UNKNOWN", statusRequest("TX-UNKNOWN", PEER_BIC, 1), UUID.randomUUID());

        await().atMost(HANDLED).until(() -> notes("TX-UNKNOWN").contains("status request for no incoming transfer"));
        assertThat(sentToCsm()).isEmpty();
        assertThat(finalEvents("TX-UNKNOWN")).isEmpty();
    }

    @Test
    void aRedeliveredStatusRequestSendsNoSecondPacs028() {
        answered("TX-REDELIVERED", PEER_BIC);
        UUID eventId = UUID.randomUUID();
        publishCommand("TX-REDELIVERED", statusRequest("TX-REDELIVERED", PEER_BIC, 1), eventId);
        await().atMost(HANDLED).until(() -> delivered("TX-REDELIVERED").size() == 1);
        int sent = sentToCsm().size();

        publishCommand("TX-REDELIVERED", statusRequest("TX-REDELIVERED", PEER_BIC, 1), eventId);

        await().during(QUIET).atMost(HANDLED).until(() -> sentToCsm().size() == sent);
        assertThat(jdbc.sql("SELECT count(*) FROM status_requests WHERE tx_id = 'TX-REDELIVERED'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    /** A valid incoming transfer, received and accepted: its ACCP delivered to the CSM. */
    private void answered(String txId, Bic debtorAgent) {
        assertThat(inbound(pacs008(msgId(txId, debtorAgent), txId, debtorAgent, CLOCK.instant())).getStatusCode()
                .value()).isEqualTo(202);
        publishCommand(txId, new AcceptCreditTransfer(new PaymentId(UUID.randomUUID()), new TxId(txId), debtorAgent,
                CLOCK.instant()), UUID.randomUUID());
        await().atMost(HANDLED).until(() -> "DELIVERED".equals(incoming(debtorAgent, txId).get("transmit_status")));
    }

    /** The CSM's status report of the incoming transfer, answering its {@code pacs.008}. */
    private String report(String txId, Bic debtorAgent, Status status) {
        return statusReport(new MsgId(msgId(txId, debtorAgent)), CsmMessageType.PACS_008, txId, debtorAgent, status);
    }

    /** The CSM's answer to our {@code pacs.028} {@code requestMsgId}. */
    private String investigationAnswer(String txId, Bic debtorAgent, String requestMsgId, Status status) {
        return statusReport(new MsgId(requestMsgId), CsmMessageType.PACS_028, txId, debtorAgent, status);
    }

    private String statusReport(MsgId original, CsmMessageType originalType, String txId, Bic debtorAgent,
            Status status) {
        return envelopes.join(new Pacs002Builder().build(new StatusReport(
                new MsgId("SIMM-IN-" + REPORTS.incrementAndGet()), CLOCK.instant(), original, originalType,
                new TxId(txId), new EndToEndId("E2E-" + txId), IncomingPacs008.uetr(txId), debtorAgent, status,
                CSM_BIC, OWN_BIC)));
    }

    private static RequestPaymentStatus statusRequest(String txId, Bic debtorAgent, int attempt) {
        return new RequestPaymentStatus(new PaymentId(UUID.randomUUID()), new TxId(txId), debtorAgent,
                Direction.INCOMING, attempt, START);
    }

    private static String msgId(String txId, Bic debtorAgent) {
        return "M-" + debtorAgent.value() + "-" + txId;
    }

    private CsmMessageType type(String xml) {
        return ((Accepted) envelopes.split(xml)).envelope().messageType();
    }

    private List<Event> finalEvents(String txId) {
        return events(txId).stream().filter(event -> event.type().startsWith("CreditTransferSettled")
                || event.type().startsWith("CreditTransferRejected")).toList();
    }

    private List<Map<String, Object>> delivered(String txId) {
        return jdbc.sql("SELECT * FROM status_requests WHERE tx_id = :txId AND transmit_status = 'DELIVERED'")
                .param("txId", txId).query().listOfRows();
    }

    private String requestMsgId(String txId, Bic debtorAgent) {
        return jdbc.sql("SELECT msg_id FROM status_requests WHERE tx_id = :txId AND debtor_agent_bic = :bic")
                .param("txId", txId).param("bic", debtorAgent.value()).query(String.class).single();
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
}
