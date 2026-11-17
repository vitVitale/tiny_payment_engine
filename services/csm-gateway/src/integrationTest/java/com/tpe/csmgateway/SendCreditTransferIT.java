package com.tpe.csmgateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.tpe.csmgateway.Commands.sendCreditTransfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.contracts.SendCreditTransfer;
import com.tpe.iso20022model.CsmMessageType;
import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.EnvelopeCodec.Accepted;
import com.tpe.iso20022model.pacs008v08.CreditTransferTransaction39;
import com.tpe.paymentdomain.Money;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@code SendCreditTransfer} to {@code pacs.008} (specs/15-csm-gateway.md §6.1, §6.5). */
class SendCreditTransferIT extends GatewayStack {

    /** Kafka delivery, the listener and the transmitter: well within this. */
    private static final Duration HANDLED = Duration.ofSeconds(15);
    /** Long enough for a message that would be sent to have been sent. */
    private static final Duration QUIET = Duration.ofSeconds(2);

    private final EnvelopeCodec envelopes = new EnvelopeCodec();

    @Test
    void GW_AC_01_aCommandBeforeSendByProducesExactlyOneValidPacs008JournalledBeforeTransmission() {
        SendCreditTransfer command = sendCreditTransfer("TX-AC01", START.minusSeconds(1));

        publishCommand("TX-AC01", command, UUID.randomUUID());

        await().atMost(HANDLED).until(() -> "DELIVERED".equals(transfer("TX-AC01").get("transmit_status")));
        assertThat(sentToCsm()).hasSize(1);
        var envelope = ((Accepted) envelopes.split(sentToCsm().getFirst())).envelope();
        assertThat(envelope.messageType()).isEqualTo(CsmMessageType.PACS_008);
        CreditTransferTransaction39 transaction = ((com.tpe.iso20022model.pacs008v08.Document) envelope.document())
                .getFIToFICstmrCdtTrf().getCdtTrfTxInf().getFirst();
        assertThat(transaction.getPmtId().getTxId()).isEqualTo("TX-AC01");
        assertThat(transaction.getPmtId().getUETR()).isEqualTo(command.uetr().value());
        assertThat(transaction.getAccptncDtTm().toGregorianCalendar().toInstant()).isEqualTo(command.acceptedAt());

        Map<String, Object> journalled = jdbc.sql("""
                SELECT direction, message_type, msg_id, valid, xml, at FROM message_journal WHERE tx_id = 'TX-AC01'""")
                .query().singleRow();
        Map<String, Object> transfer = transfer("TX-AC01");
        assertThat(journalled).containsEntry("direction", "OUT").containsEntry("message_type", "pacs.008.001.08")
                .containsEntry("valid", true).containsEntry("msg_id", transfer.get("msg_id"));
        assertThat(journalled.get("xml")).as("what was journalled is what was sent").isEqualTo(sentToCsm().getFirst());
        assertThat(transfer).containsEntry("send_status", "SENT").containsEntry("transmit_attempts", 1);
        assertThat(((Timestamp) journalled.get("at")).toInstant()).isBeforeOrEqualTo(
                ((Timestamp) transfer.get("delivered_at")).toInstant());
        assertThat(events("TX-AC01")).isEmpty();
        assertThat(CSM.findAll(com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/csm/v1/messages"))).getFirst()
                .containsHeader("traceparent")).as("no trace headers to the CSM (ADR-014 §5)").isFalse();
    }

    @Test
    void GW_AC_02_aCommandAtSendByProducesNoPacs008AndOneCreditTransferNotSentAb03() {
        SendCreditTransfer command = sendCreditTransfer("TX-AC02", START.minusSeconds(10));

        publishCommand("TX-AC02", command, UUID.randomUUID());

        await().atMost(HANDLED).until(() -> !events("TX-AC02").isEmpty());
        assertThat(events("TX-AC02")).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("CreditTransferNotSent");
            assertThat(event.payload()).contains("\"reasonCode\": \"AB03\"", "\"direction\": \"OUTGOING\"",
                    "\"paymentId\": \"" + command.paymentId().value() + "\"");
        });
        assertThat(transfer("TX-AC02")).containsEntry("send_status", "NOT_SENT")
                .containsEntry("not_sent_reason", "AB03").containsEntry("transmit_status", null);
        assertThat(jdbc.sql("SELECT count(*) FROM message_journal WHERE tx_id = 'TX-AC02'").query(Integer.class)
                .single()).isZero();
        await().during(QUIET).atMost(HANDLED).until(() -> sentToCsm().isEmpty());
    }

    @Test
    void aPacs008ThatFailsItsXsdIsNotSentAndPublishedAsCreditTransferNotSentFf01() {
        SendCreditTransfer valid = sendCreditTransfer("TX-FF01", START.minusSeconds(1));
        // 20 digits: more than the 18 total digits of ActiveCurrencyAndAmount, so the built pacs.008 is invalid
        SendCreditTransfer tooLarge = new SendCreditTransfer(valid.paymentId(), valid.txId(), valid.uetr(),
                valid.endToEndId(), valid.acceptedAt(), valid.sendBy(), valid.deadlineAt(),
                new Money(Long.MAX_VALUE, valid.amount().currency()), valid.debtor(), valid.debtorAgentBic(),
                valid.creditor(), valid.creditorAgentBic(), valid.remittanceInformation());

        publishCommand("TX-FF01", tooLarge, UUID.randomUUID());

        await().atMost(HANDLED).until(() -> !events("TX-FF01").isEmpty());
        assertThat(events("TX-FF01")).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("CreditTransferNotSent");
            assertThat(event.payload()).contains("\"reasonCode\": \"FF01\"");
        });
        assertThat(transfer("TX-FF01")).containsEntry("send_status", "NOT_SENT")
                .containsEntry("not_sent_reason", "FF01");
        assertThat(jdbc.sql("SELECT valid FROM message_journal WHERE tx_id = 'TX-FF01'").query(Boolean.class)
                .single()).as("journalled, invalid").isFalse();
        await().during(QUIET).atMost(HANDLED).until(() -> sentToCsm().isEmpty());
    }

    @Test
    void aRedeliveredCommandAndASecondCommandForTheSameTxIdSendNothingNew() {
        SendCreditTransfer command = sendCreditTransfer("TX-AGAIN", START.minusSeconds(1));
        UUID eventId = UUID.randomUUID();

        publishCommand("TX-AGAIN", command, eventId);
        await().atMost(HANDLED).until(() -> sentToCsm().size() == 1);
        publishCommand("TX-AGAIN", command, eventId);
        publishCommand("TX-AGAIN", command, UUID.randomUUID());

        await().during(QUIET).atMost(HANDLED).until(() -> sentToCsm().size() == 1);
        assertThat(jdbc.sql("SELECT count(*) FROM message_journal WHERE tx_id = 'TX-AGAIN'").query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM inbox").query(Integer.class).single())
                .as("the second command has its own eventId, the same dedup key").isEqualTo(1);
    }

    @Test
    void aTransmissionTheCsmRefusesLeavesTheTransferPendingForTheRetries() {
        CSM.stubFor(post("/csm/v1/messages").willReturn(aResponse().withStatus(503)));

        publishCommand("TX-DOWN", sendCreditTransfer("TX-DOWN", START.minusSeconds(1)), UUID.randomUUID());

        await().atMost(HANDLED).until(() -> Integer.valueOf(1).equals(transfer("TX-DOWN").get("transmit_attempts")));
        await().during(QUIET).atMost(HANDLED).until(() -> "PENDING".equals(transfer("TX-DOWN").get("transmit_status")));
        assertThat(transfer("TX-DOWN")).containsEntry("send_status", "SENT");
    }

    @Test
    void thePaymentsTraceIsStoredWithTheTransfer() {
        publishCommand("TX-TRACE", sendCreditTransfer("TX-TRACE", START.minusSeconds(1)), UUID.randomUUID());

        await().atMost(HANDLED).until(() -> !transfer("TX-TRACE").isEmpty());
        assertThat((String) transfer("TX-TRACE").get("trace_context")).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
    }

}
