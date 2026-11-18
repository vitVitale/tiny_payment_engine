package com.tpe.csmsimulator;

import static com.tpe.csmsimulator.CreditTransfers.answer;
import static com.tpe.csmsimulator.CreditTransfers.investigation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.iso20022model.CsmMessageType;
import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.EnvelopeCodec.Accepted;
import com.tpe.iso20022model.Pacs002Builder.Status;
import com.tpe.paymentdomain.ReasonCode;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Incoming payments through the control API against a stub csm-gateway (specs/17-csm-simulator.md §3.2, §3.5): the
 * CSM delivers a {@code pacs.008}, waits for our answer and settles an {@code ACCP}.
 */
class IncomingPaymentIT extends SimulatorStack {

    /** The timeline runs every {@code tpe.sim.tick} (5 ms); work that is due is done well within this. */
    private static final Duration DUE_WORK = Duration.ofSeconds(2);
    /** An account of ours; the debtor's is at the peer bank, {@code PEERDEFFXXX} in deploy/config/bank-directory.yml. */
    private static final String OUR_ACCOUNT = "DE36100100100000000001";
    private static final String PEER_ACCOUNT = "DE07200200200123456789";

    private final EnvelopeCodec envelopes = new EnvelopeCodec();

    @Test
    void anIncomingPaymentAcceptedInTimeIsSettledAndConfirmed() throws Exception {
        var created = createIncomingPayment("{\"creditorIban\": \"" + OUR_ACCOUNT + "\", \"amount\": \"12.34\","
                + " \"debtorName\": \"Hans Mueller\", \"debtorIban\": \"" + PEER_ACCOUNT + "\","
                + " \"scenario\": \"ACCEPT\"}");
        assertThat(created.statusCode()).isEqualTo(200);
        assertThat(created.body()).contains("\"gatewayStatus\":202");
        String txId = txId(created.body());
        assertThat(sentToGateway()).hasSize(1);
        String incoming = sentToGateway().getFirst();
        assertThat(incoming).contains("<TxId>" + txId + "</TxId>", "<BICFI>PEERDEFFXXX</BICFI>",
                "<BICFI>TPEBDEFFXXX</BICFI>");

        CLOCK.advance(Duration.ofSeconds(1));
        assertThat(transmit(answer("ANS-1", incoming, Status.ACCEPTED, CLOCK.instant())).statusCode()).isEqualTo(202);

        await().atMost(DUE_WORK).until(() -> sentToGateway().size() == 2);
        String confirmation = sentToGateway().get(1);
        assertThat(((Accepted) envelopes.split(confirmation)).envelope().messageType())
                .isEqualTo(CsmMessageType.PACS_002);
        assertThat(confirmation).contains("<TxSts>ACSC</TxSts>", "<OrgnlTxId>" + txId + "</OrgnlTxId>");
        assertThat(get("/sim/v1/settlements").body()).contains("\"txId\":\"" + txId + "\"",
                "\"debtorAgentBic\":\"PEERDEFFXXX\"", "\"direction\":\"INCOMING\"", "\"amountMinor\":1234",
                "\"settledAt\":\"2026-11-15T10:00:01Z\"");
        assertThat(get("/sim/v1/received?txId=" + txId).body())
                .containsSubsequence("\"kind\":\"SENT\",\"messageType\":\"pacs.008.001.08\"",
                        "\"kind\":\"RECEIVED\",\"messageType\":\"pacs.002.001.10\"",
                        "\"kind\":\"SENT\",\"messageType\":\"pacs.002.001.10\"");
    }

    @Test
    void SIM_AC_07_anExplicitTxIdAndDebtorAgentAreCarriedAndARejectedOneIsNotSettled() throws Exception {
        var created = createIncomingPayment("{\"creditorIban\": \"" + OUR_ACCOUNT + "\", \"amount\": \"5.00\","
                + " \"debtorName\": \"Hans Mueller\", \"debtorIban\": \"" + PEER_ACCOUNT + "\","
                + " \"scenario\": \"ACCEPT\", \"txId\": \"PEER-TX-1\", \"debtorAgentBic\": \"OTHRDEFFXXX\"}");
        assertThat(created.body()).contains("\"txId\":\"PEER-TX-1\"");
        String incoming = sentToGateway().getFirst();
        assertThat(incoming).contains("<TxId>PEER-TX-1</TxId>", "<BICFI>OTHRDEFFXXX</BICFI>");

        transmit(answer("ANS-1", incoming, Status.rejected(ReasonCode.AC04), CLOCK.instant()));
        await().atMost(DUE_WORK).until(() -> get("/sim/v1/received?txId=PEER-TX-1").body().contains("RECEIVED"));
        CLOCK.advance(Duration.ofMinutes(1));

        assertThat(get("/sim/v1/settlements").body()).isEqualTo("[]");
        assertThat(sentToGateway()).hasSize(1);
    }

    @Test
    void rejectAfterAccpAnswersOurAccpWithOneRejectionAb05AndNoSettlement() throws Exception {
        String txId = txId(createIncomingPayment(body("REJECT_AFTER_ACCP")).body());
        String incoming = sentToGateway().getFirst();

        transmit(answer("ANS-1", incoming, Status.ACCEPTED, CLOCK.instant()));

        await().atMost(DUE_WORK).until(() -> sentToGateway().size() == 2);
        assertThat(sentToGateway().get(1)).contains("<TxSts>RJCT</TxSts>", "<Cd>AB05</Cd>",
                "<OrgnlTxId>" + txId + "</OrgnlTxId>");
        CLOCK.advance(Duration.ofMinutes(1));
        assertThat(get("/sim/v1/settlements").body()).isEqualTo("[]");
        assertThat(sentToGateway()).hasSize(2);
    }

    @Test
    void noConfirmationSettlesWithoutAConfirmationAndAnInvestigationFindsItSettled() throws Exception {
        String txId = txId(createIncomingPayment(body("NO_CONFIRMATION")).body());
        String incoming = sentToGateway().getFirst();

        transmit(answer("ANS-1", incoming, Status.ACCEPTED, CLOCK.instant()));
        await().atMost(DUE_WORK).until(() -> get("/sim/v1/settlements").body().contains(txId));
        CLOCK.advance(Duration.ofMinutes(1));
        assertThat(sentToGateway()).as("no confirmation").hasSize(1);

        transmit(investigation("INV-1", incoming, CLOCK.instant()));

        await().atMost(DUE_WORK).until(() -> sentToGateway().size() == 2);
        assertThat(sentToGateway().get(1)).contains("<TxSts>ACSC</TxSts>", "<OrgnlMsgId>INV-1</OrgnlMsgId>",
                "<OrgnlTxId>" + txId + "</OrgnlTxId>", "<BICFI>PEERDEFFXXX</BICFI>");
    }

    @Test
    void aRawDocumentWithADoctypeGoesToTheGatewayUnchanged() throws Exception {
        String withDoctype = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<x>&e;</x>";

        var response = send("POST", "/sim/v1/incoming-payments/raw", "application/xml", withDoctype);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"gatewayStatus\":202}");
        assertThat(sentToGateway()).containsExactly(withDoctype);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"amount\": \"1.00\", \"debtorName\": \"X\", \"debtorIban\": \"" + PEER_ACCOUNT + "\"}",
            "{\"creditorIban\": \"" + OUR_ACCOUNT + "\", \"amount\": \"1.00\", \"debtorName\": \"X\","
                    + " \"debtorIban\": \"" + PEER_ACCOUNT + "\", \"scenario\": \"TIMEOUT\"}",
            "{\"creditorIban\": \"" + OUR_ACCOUNT + "\", \"amount\": \"1.00\", \"debtorName\": \"X\","
                    + " \"debtorIban\": \"DE89370400440532013000\"}",
            "{\"creditorIban\": \"" + OUR_ACCOUNT + "\", \"amount\": \"abc\", \"debtorName\": \"X\","
                    + " \"debtorIban\": \"" + PEER_ACCOUNT + "\"}"})
    void anIncomingPaymentItCannotSendIs400(String body) throws Exception {
        var response = createIncomingPayment(body);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"VALIDATION_FAILED\"", "\"errors\":[{");
        assertThat(sentToGateway()).isEmpty();
    }

    private java.net.http.HttpResponse<String> createIncomingPayment(String body) throws Exception {
        return send("POST", "/sim/v1/incoming-payments", "application/json", body);
    }

    private static String body(String scenario) {
        return "{\"creditorIban\": \"" + OUR_ACCOUNT + "\", \"amount\": \"12.34\", \"debtorName\": \"Hans Mueller\","
                + " \"debtorIban\": \"" + PEER_ACCOUNT + "\", \"scenario\": \"" + scenario + "\"}";
    }

    private static String txId(String body) {
        return body.replaceAll(".*\"txId\":\"([^\"]+)\".*", "$1");
    }
}
