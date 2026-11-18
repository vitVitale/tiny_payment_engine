package com.tpe.csmgateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.tpe.csmgateway.IncomingPacs008.OTHER_BIC;
import static com.tpe.csmgateway.IncomingPacs008.OWN_BIC;
import static com.tpe.csmgateway.IncomingPacs008.PEER_BIC;
import static com.tpe.csmgateway.IncomingPacs008.pacs008;
import static com.tpe.csmgateway.IncomingPacs008.schemaInvalid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.iso20022model.CsmMessageType;
import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.EnvelopeCodec.Accepted;
import com.tpe.iso20022model.pacs002v10.PaymentTransaction110;
import com.tpe.paymentdomain.Bic;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** An incoming {@code pacs.008} (specs/15-csm-gateway.md §6.2, §6.5, ADR-010, UC-06 1, 1a, 1b). */
class IncomingCreditTransferIT extends GatewayStack {

    private static final Duration HANDLED = Duration.ofSeconds(15);

    private final EnvelopeCodec envelopes = new EnvelopeCodec();

    @Test
    void GW_AC_08_aValidPacs008IsRecordedAndHandedToTheOrchestratorWithItsAnswerDeadline() {
        assertThat(inbound(pacs008("PEERM-1", "PEER-TX-1", PEER_BIC, START)).getStatusCode().value())
                .isEqualTo(202);

        assertThat(incoming(PEER_BIC, "PEER-TX-1"))
                .containsEntry("msg_id", "PEERM-1")
                .containsEntry("received_at", Timestamp.from(START))
                .containsEntry("answer_deadline", Timestamp.from(START.plusSeconds(5)))
                .containsEntry("answer_status", null)
                .containsEntry("transmit_status", null);
        assertThat((String) incoming(PEER_BIC, "PEER-TX-1").get("trace_context"))
                .as("the root of the incoming payment's trace").startsWith("00-");
        assertThat(events("PEER-TX-1")).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("CreditTransferReceived");
            assertThat(event.payload()).contains("\"txId\": \"PEER-TX-1\"", "\"debtorAgentBic\": \"PEERDEFFXXX\"",
                    "\"direction\": \"INCOMING\"", "\"msgId\": \"PEERM-1\"",
                    "\"receivedAt\": \"2026-11-15T10:00:00.000Z\"", "\"answerDeadline\": \"2026-11-15T10:00:05.000Z\"",
                    "\"amountMinor\": 1234", "\"currency\": \"EUR\"", "\"creditorAgentBic\": \"TPEBDEFFXXX\"",
                    "\"iban\": \"DE36100100100000000001\"", "\"remittanceInformation\": \"Invoice PEER-TX-1\"");
        });
        assertThat(journal("PEER-TX-1")).singleElement().satisfies(entry -> assertThat(entry)
                .containsEntry("direction", "IN").containsEntry("message_type", "pacs.008.001.08")
                .containsEntry("debtor_agent_bic", "PEERDEFFXXX").containsEntry("valid", true)
                .containsEntry("note", null));
        assertThat(sentToCsm()).as("the orchestrator decides the answer").isEmpty();
    }

    @Test
    void GW_AC_09_aSchemaInvalidPacs008IsAnsweredRejectedFf01AndPublishesNothing() {
        assertThat(inbound(schemaInvalid(pacs008("PEERM-1", "PEER-TX-1", PEER_BIC, START))).getStatusCode().value())
                .isEqualTo(202);

        assertThat(incoming(PEER_BIC, "PEER-TX-1"))
                .containsEntry("answer_status", "RJCT").containsEntry("answer_reason", "FF01")
                .containsEntry("answer_decided_by", "VALIDATION")
                .containsEntry("answered_at", Timestamp.from(START));
        await().atMost(HANDLED).until(() -> "DELIVERED".equals(incoming(PEER_BIC, "PEER-TX-1").get("transmit_status")));
        assertThat(sentToCsm()).singleElement().satisfies(answer -> {
            PaymentTransaction110 transaction = statusReport(answer);
            assertThat(transaction.getTxSts()).isEqualTo("RJCT");
            assertThat(transaction.getStsRsnInf().getFirst().getRsn().getCd()).isEqualTo("FF01");
            assertThat(transaction.getOrgnlTxId()).isEqualTo("PEER-TX-1");
            assertThat(transaction.getOrgnlTxRef().getDbtrAgt().getFinInstnId().getBICFI())
                    .isEqualTo(PEER_BIC.value());
            assertThat(transaction.getInstgAgt().getFinInstnId().getBICFI()).isEqualTo(OWN_BIC.value());
            assertThat(transaction.getInstdAgt().getFinInstnId().getBICFI()).isEqualTo("TIPSXXXXXXX");
            assertThat(answer).contains("<OrgnlMsgId>PEERM-1</OrgnlMsgId>");
        });
        assertThat(events("PEER-TX-1")).isEmpty();
        assertThat(journal("PEER-TX-1")).extracting(e -> e.get("direction"), e -> e.get("valid"), e -> e.get("note"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple("IN", false, "invalid, answered RJCT FF01"),
                        org.assertj.core.groups.Tuple.tuple("OUT", true, null));
    }

    @Test
    void aPacs008ForAnotherBankOrInAnotherCurrencyIsAnsweredRejectedFf01() {
        inbound(pacs008("PEERM-1", "PEER-TX-1", PEER_BIC, OTHER_BIC, START));
        inbound(pacs008("PEERM-2", "PEER-TX-2", PEER_BIC, START).replace("Ccy=\"EUR\"", "Ccy=\"USD\""));

        assertThat(incoming(PEER_BIC, "PEER-TX-1")).containsEntry("answer_reason", "FF01");
        assertThat(incoming(PEER_BIC, "PEER-TX-2")).containsEntry("answer_reason", "FF01");
        assertThat(journal("PEER-TX-1").getFirst().get("validation_errors").toString())
                .contains("NOT_OUR_CREDITOR_AGENT");
        assertThat(journal("PEER-TX-2").getFirst().get("validation_errors").toString()).contains("NOT_EUR");
        assertThat(events("PEER-TX-1")).isEmpty();
        assertThat(events("PEER-TX-2")).isEmpty();
    }

    @Test
    void GW_AC_10_aDuplicateBeforeTheAnswerIsDroppedAndOneAfterItGetsTheSameAnswerAgain() {
        String valid = pacs008("PEERM-1", "PEER-TX-1", PEER_BIC, START);
        inbound(valid);
        inbound(valid);

        assertThat(events("PEER-TX-1")).hasSize(1);
        assertThat(journal("PEER-TX-1")).extracting(e -> e.get("note"))
                .containsExactly(null, "duplicate pacs.008 dropped");

        String invalid = schemaInvalid(pacs008("PEERM-2", "PEER-TX-2", PEER_BIC, START));
        inbound(invalid);
        await().atMost(HANDLED).until(() -> sentToCsm().size() == 1);
        inbound(invalid);

        await().atMost(HANDLED).until(() -> sentToCsm().size() == 2);
        assertThat(sentToCsm().get(1)).as("the same pacs.002, same MsgId").isEqualTo(sentToCsm().get(0));
        assertThat(journal("PEER-TX-2")).extracting(e -> e.get("note"))
                .containsExactly("invalid, answered RJCT FF01", null, "duplicate pacs.008, answer re-sent");
    }

    @Test
    void GW_AC_14_aPacs008WithADoctypeIsAnsweredRejectedFf01WithoutResolvingAnEntity(@TempDir Path dir)
            throws Exception {
        Path secret = Files.writeString(dir.resolve("secret.txt"), "LEAKED-SECRET");
        String withDoctype = pacs008("PEERM-1", "PEER-TX-1", PEER_BIC, START)
                .replaceFirst("<Envelope ", "<!DOCTYPE Envelope [<!ENTITY xxe SYSTEM \"" + secret.toUri()
                        + "\">]><Envelope ")
                .replace("<Nm>Hans Mueller</Nm>", "<Nm>&xxe;</Nm>");

        assertThat(inbound(withDoctype).getStatusCode().value()).isEqualTo(202);

        assertThat(incoming(PEER_BIC, "PEER-TX-1")).containsEntry("answer_reason", "FF01");
        assertThat(journal("PEER-TX-1").getFirst().get("validation_errors").toString())
                .contains("DOCTYPE_DISALLOWED");
        assertThat(events("PEER-TX-1")).isEmpty();
        await().atMost(HANDLED).until(() -> sentToCsm().size() == 1);
        assertThat(jdbc.sql("SELECT count(*) FROM message_journal WHERE xml LIKE '%LEAKED-SECRET%'")
                .query(Integer.class).single()).isZero();
        assertThat(sentToCsm().getFirst()).doesNotContain("LEAKED-SECRET");
    }

    @Test
    void theSameTxIdFromTwoDebtorAgentsIsTwoTransactions() {
        inbound(pacs008("PEERM-1", "TX-SHARED", PEER_BIC, START));
        inbound(pacs008("OTHRM-1", "TX-SHARED", OTHER_BIC, START));

        assertThat(incoming(PEER_BIC, "TX-SHARED")).isNotEmpty();
        assertThat(incoming(OTHER_BIC, "TX-SHARED")).isNotEmpty();
        assertThat(events("TX-SHARED")).extracting(Event::type)
                .containsExactly("CreditTransferReceived", "CreditTransferReceived");
        assertThat(events("TX-SHARED")).extracting(Event::payload).anySatisfy(p -> assertThat(p)
                .contains("\"debtorAgentBic\": \"PEERDEFFXXX\"")).anySatisfy(p -> assertThat(p)
                .contains("\"debtorAgentBic\": \"OTHRDEFFXXX\""));
    }

    @Test
    void aMessageWithoutAReadableTxIdIs400AndNothingIsJournalled() {
        String valid = pacs008("PEERM-1", "PEER-TX-1", PEER_BIC, START);
        String noTxId = valid.substring(0, valid.indexOf("<TxId>"));

        var response = inbound(noTxId);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("\"code\":\"INVALID_ENVELOPE\"");
        assertThat(jdbc.sql("SELECT count(*) FROM message_journal").query(Integer.class).single()).isZero();
    }

    @Test
    void aPacs008InAParsableEnvelopeWithoutATxIdIs400AndNothingIsJournalled() {
        String noTxId = pacs008("PEERM-1", "PEER-TX-1", PEER_BIC, START).replace("<TxId>PEER-TX-1</TxId>", "");

        var response = inbound(noTxId);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("\"code\":\"INVALID_ENVELOPE\"", "UNREADABLE_IDENTITY");
        assertThat(jdbc.sql("SELECT count(*) FROM message_journal").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM incoming_transfers").query(Integer.class).single()).isZero();
    }

    @Test
    void anAnswerTheCsmDoesNotTakeIsRetriedUntilTheTransmitWindowCloses() {
        CSM.stubFor(post("/csm/v1/messages").willReturn(aResponse().withStatus(503)));
        inbound(schemaInvalid(pacs008("PEERM-1", "PEER-TX-1", PEER_BIC, START)));
        // the first attempt failed and its retry is set, before the clock moves
        await().atMost(HANDLED).until(() -> Timestamp.from(START.plusMillis(200))
                .equals(incoming(PEER_BIC, "PEER-TX-1").get("next_transmit_at")));

        CLOCK.set(START.plusSeconds(5).plusMillis(999));
        await().atMost(HANDLED).until(() -> sentToCsm().size() == 2);
        CLOCK.set(START.plusSeconds(7));

        await().atMost(HANDLED).until(() -> "GAVE_UP".equals(incoming(PEER_BIC, "PEER-TX-1").get("transmit_status")));
        assertThat(sentToCsm()).as("no attempt after answer_deadline + window").hasSize(2)
                .allSatisfy(answer -> assertThat(answer).isEqualTo(sentToCsm().getFirst()));
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

    private List<Map<String, Object>> journal(String txId) {
        return jdbc.sql("SELECT * FROM message_journal WHERE tx_id = :txId ORDER BY id").param("txId", txId)
                .query().listOfRows();
    }
}
