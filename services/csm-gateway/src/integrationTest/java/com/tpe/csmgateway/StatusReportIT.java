package com.tpe.csmgateway;

import static com.tpe.csmgateway.Commands.OWN_BIC;
import static com.tpe.csmgateway.Commands.PEER_BIC;
import static com.tpe.csmgateway.Commands.sendCreditTransfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.contracts.SendCreditTransfer;
import com.tpe.iso20022model.CsmMessageType;
import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.Pacs002Builder;
import com.tpe.iso20022model.Pacs002Builder.Status;
import com.tpe.iso20022model.Pacs002Builder.StatusReport;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.Uetr;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A {@code pacs.002} for an outgoing transfer (specs/15-csm-gateway.md §6.4, UC-04 6–8). */
class StatusReportIT extends GatewayStack {

    private static final Duration HANDLED = Duration.ofSeconds(15);
    private static final Bic CSM_BIC = new Bic("TIPSXXXXXXX");
    private static final AtomicInteger REPORTS = new AtomicInteger();

    private final EnvelopeCodec envelopes = new EnvelopeCodec();

    @Autowired
    private MeterRegistry meters;

    @Test
    void settledRecordsTheFinalStatusAndPublishesCreditTransferSettled() {
        Sent sent = sent("TX-ACSC");

        assertThat(inbound(report(sent, Status.SETTLED)).getStatusCode().value()).isEqualTo(202);

        assertThat(transfer("TX-ACSC")).containsEntry("final_status", "SETTLED").containsEntry("final_reason", null);
        assertThat(events("TX-ACSC")).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("CreditTransferSettled");
            assertThat(event.payload()).contains("\"source\": \"STATUS_REPORT\"", "\"direction\": \"OUTGOING\"",
                    "\"debtorAgentBic\": \"TPEBDEFFXXX\"", "\"paymentId\": \"" + sent.command.paymentId().value());
        });
    }

    @Test
    void rejectedRecordsTheReasonAndPublishesCreditTransferRejected() {
        Sent sent = sent("TX-RJCT");

        inbound(report(sent, Status.rejected(ReasonCode.AM04)));

        assertThat(transfer("TX-RJCT")).containsEntry("final_status", "REJECTED").containsEntry("final_reason", "AM04");
        assertThat(events("TX-RJCT")).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("CreditTransferRejected");
            assertThat(event.payload()).contains("\"reasonCode\": \"AM04\"", "\"source\": \"STATUS_REPORT\"");
        });
    }

    @Test
    void GW_AC_13_aDuplicateStatusPublishesNothingAndAContradictingOneIsPublishedWhileTheFirstStays() {
        Sent sent = sent("TX-AC13");
        inbound(report(sent, Status.SETTLED));

        inbound(report(sent, Status.SETTLED));
        assertThat(events("TX-AC13")).extracting(Event::type).containsExactly("CreditTransferSettled");

        inbound(report(sent, Status.rejected(ReasonCode.AB05)));
        assertThat(events("TX-AC13")).extracting(Event::type)
                .containsExactly("CreditTransferSettled", "CreditTransferRejected");
        assertThat(transfer("TX-AC13")).containsEntry("final_status", "SETTLED");
        assertThat(notes("TX-AC13")).containsExactly(null, null, "duplicate status", "contradicting status, first kept");
    }

    @Test
    void theEventContinuesThePaymentsStoredTrace() {
        Sent sent = sent("TX-TRACE");
        String stored = (String) transfer("TX-TRACE").get("trace_context");

        inbound(report(sent, Status.SETTLED));

        String headers = jdbc.sql("SELECT headers::text FROM outbox WHERE message_key = 'TX-TRACE'")
                .query(String.class).single();
        String traceId = stored.split("-")[1];
        assertThat(headers).as("the payment's trace id (ADR-014)").contains("00-" + traceId + "-");
    }

    @Test
    void acceptedIsJournalledOnly() {
        Sent sent = sent("TX-ACCP");

        inbound(report(sent, Status.ACCEPTED));

        assertThat(events("TX-ACCP")).isEmpty();
        assertThat(transfer("TX-ACCP")).containsEntry("final_status", null);
        assertThat(notes("TX-ACCP")).last().isEqualTo("informational, no effect");
    }

    @Test
    void aReportNamingAnotherTxIdIsMatchedByTheOriginalMsgId() {
        Sent sent = sent("TX-BYMSG");

        inbound(envelopes.join(new Pacs002Builder().build(new StatusReport(msgId(), START, new MsgId(sent.msgId),
                CsmMessageType.PACS_008, new com.tpe.paymentdomain.TxId("TX-OTHER"), sent.command.endToEndId(),
                sent.command.uetr(), OWN_BIC, Status.SETTLED, CSM_BIC, OWN_BIC))));

        assertThat(transfer("TX-BYMSG")).containsEntry("final_status", "SETTLED");
    }

    @Test
    void anUnmatchedReportIsJournalledUncorrelatedAndCounted() {
        double before = uncorrelated();

        inbound(envelopes.join(new Pacs002Builder().build(new StatusReport(msgId(), START, new MsgId("NOPE"),
                CsmMessageType.PACS_008, new com.tpe.paymentdomain.TxId("TX-NOBODY"), null,
                new Uetr(UUID.randomUUID().toString()), OWN_BIC, Status.SETTLED, CSM_BIC, OWN_BIC))));

        assertThat(notes("TX-NOBODY")).containsExactly("uncorrelated");
        assertThat(uncorrelated()).isEqualTo(before + 1);
    }

    @Test
    void aReportForAnotherDebtorAgentIsNotMatchedToOurTransfer() {
        Sent sent = sent("TX-PEER");

        inbound(envelopes.join(new Pacs002Builder().build(new StatusReport(msgId(), START, new MsgId("PEER-MSG"),
                CsmMessageType.PACS_008, sent.command.txId(), null, null, PEER_BIC, Status.SETTLED, CSM_BIC,
                OWN_BIC))));

        assertThat(transfer("TX-PEER")).containsEntry("final_status", null);
        assertThat(notes("TX-PEER")).last().isEqualTo("uncorrelated");
    }

    @Test
    void anUnparsableEnvelopeIs400AndNothingIsJournalled() {
        var response = inbound("not xml");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("\"code\":\"INVALID_ENVELOPE\"");
        assertThat(jdbc.sql("SELECT count(*) FROM message_journal").query(Integer.class).single()).isZero();
    }

    @Test
    void aSchemaInvalidReportIsJournalledInvalidAndAcknowledged() {
        Sent sent = sent("TX-BAD");
        String invalid = report(sent, Status.SETTLED).replace("<OrgnlMsgNmId>pacs.008.001.08</OrgnlMsgNmId>", "");

        assertThat(inbound(invalid).getStatusCode().value()).isEqualTo(202);

        assertThat(jdbc.sql("SELECT count(*) FROM message_journal WHERE NOT valid AND validation_errors IS NOT NULL")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(transfer("TX-BAD")).containsEntry("final_status", null);
    }

    /** A transfer the gateway sent and the CSM took, with its {@code pacs.008}'s {@code MsgId}. */
    private Sent sent(String txId) {
        SendCreditTransfer command = sendCreditTransfer(txId, START.minusSeconds(1));
        publishCommand(txId, command, UUID.randomUUID());
        await().atMost(HANDLED).until(() -> "DELIVERED".equals(transfer(txId).get("transmit_status")));
        return new Sent(command, (String) transfer(txId).get("msg_id"));
    }

    private String report(Sent sent, Status status) {
        return envelopes.join(new Pacs002Builder().build(new StatusReport(msgId(), START, new MsgId(sent.msgId),
                CsmMessageType.PACS_008, sent.command.txId(), sent.command.endToEndId(), sent.command.uetr(),
                OWN_BIC, status, CSM_BIC, OWN_BIC)));
    }

    private static MsgId msgId() {
        return new MsgId("SIMM-" + REPORTS.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private java.util.List<String> notes(String txId) {
        return jdbc.sql("SELECT note FROM message_journal WHERE tx_id = :txId ORDER BY id").param("txId", txId)
                .query((rs, n) -> rs.getString("note")).list();
    }

    private double uncorrelated() {
        var counter = meters.find("tpe.csm.uncorrelated").counter();
        return counter == null ? 0 : counter.count();
    }

    private record Sent(SendCreditTransfer command, String msgId) {}
}
