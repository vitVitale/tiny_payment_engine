package com.tpe.csmsimulator.infrastructure.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.tpe.iso20022model.CsmMessageType;
import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.Pacs002Builder;
import com.tpe.iso20022model.Pacs002Builder.Status;
import com.tpe.iso20022model.Pacs002Builder.StatusReport;
import com.tpe.iso20022model.Pacs008Builder;
import com.tpe.iso20022model.Pacs008Builder.AccountHolder;
import com.tpe.iso20022model.Pacs008Builder.CreditTransfer;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.web.client.RestClient;

/**
 * P-07, consumer csm-simulator (specs/20-testing-strategy.md §10.1, §10.2): {@code POST /csm/v1/inbound} through the
 * real {@link HttpGatewayClient}. The pact covers the envelope and the transport answer (§10, XML bodies): {@code 202}
 * for a {@code pacs.008} and a {@code pacs.002}, {@code 202} for a schema-invalid {@code pacs.008} whose {@code TxId}
 * can be read (csm-gateway answers it {@code RJCT FF01} later), {@code 400} for one with no readable {@code TxId}.
 * The documents are built as the simulator builds them; their content is the XSDs' job.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = CsmGatewayPactTest.PROVIDER, pactVersion = PactSpecVersion.V4)
class CsmGatewayPactTest {

    static final String CONSUMER = "csm-simulator";
    static final String PROVIDER = "csm-gateway";

    private static final Instant AT = Instant.parse("2026-11-15T10:00:00.123Z");
    private static final Bic CSM = new Bic("TIPSXXXXXXX");
    private static final Bic OURS = new Bic("TPEBDEFFXXX");
    private static final Bic PEER = new Bic("PEERDEFFXXX");
    private static final MsgId PACS_008_MSG_ID = new MsgId("SIMM01JCSMPACT0000000000000008");
    private static final TxId TX_ID = new TxId("SIMT01JCSMPACT00000000000000TX");
    private static final EndToEndId END_TO_END_ID = new EndToEndId("SIME01JCSMPACT000000000000E2E");
    private static final Uetr UETR = new Uetr("4b4c3f1d-2c1a-4f0e-9a51-6b1f0c2d7e01");

    /** Well-formed XML, but no CSM envelope and no {@code TxId} to read. */
    private static final String NOT_AN_ENVELOPE = "<NotAnEnvelope xmlns=\"urn:example\"/>";

    private static final EnvelopeCodec ENVELOPES = new EnvelopeCodec();

    /** An incoming payment: from a peer bank's customer to one of ours. */
    static String pacs008() {
        return ENVELOPES.join(new Pacs008Builder().build(new CreditTransfer(PACS_008_MSG_ID, AT, TX_ID, END_TO_END_ID,
                UETR, Money.parse("12.34"), LocalDate.parse("2026-11-15"), AT,
                new AccountHolder(new PartyName("Hans Mueller"), new Iban("DE07200200200123456789")), PEER, OURS,
                new AccountHolder(new PartyName("Account Holder"), new Iban("DE36100100100000000001")), null)));
    }

    /** The settlement confirmation of an incoming payment. */
    static String pacs002() {
        return ENVELOPES.join(new Pacs002Builder().build(new StatusReport(new MsgId("SIMM01JCSMPACT0000000000000002"),
                AT, PACS_008_MSG_ID, CsmMessageType.PACS_008, TX_ID, END_TO_END_ID, UETR, PEER, Status.SETTLED, CSM,
                OURS)));
    }

    /** A {@code pacs.008} without its mandatory {@code ChrgBr}: schema-invalid, its {@code TxId} still readable. */
    static String schemaInvalidPacs008() {
        return pacs008().replace("<ChrgBr>SLEV</ChrgBr>", "");
    }

    @Pact(consumer = CONSUMER)
    V4Pact takesAPacs008(PactDslWithProvider builder) {
        return answered(builder, "a pacs.008 envelope", pacs008(), 202);
    }

    @Pact(consumer = CONSUMER)
    V4Pact takesAPacs002(PactDslWithProvider builder) {
        return answered(builder, "a pacs.002 envelope", pacs002(), 202);
    }

    @Pact(consumer = CONSUMER)
    V4Pact takesASchemaInvalidPacs008WithAReadableTxId(PactDslWithProvider builder) {
        return answered(builder, "a schema-invalid pacs.008 envelope with a readable TxId", schemaInvalidPacs008(),
                202);
    }

    @Pact(consumer = CONSUMER)
    V4Pact refusesAMessageWithoutAReadableTxId(PactDslWithProvider builder) {
        return answered(builder, "a message without a readable TxId", NOT_AN_ENVELOPE, 400);
    }

    @Test
    @PactTestFor(pactMethod = "takesAPacs008")
    void PACT_P07_aPacs008IsTaken(MockServer gateway) {
        assertThat(client(gateway).post(pacs008())).isEqualTo(202);
    }

    @Test
    @PactTestFor(pactMethod = "takesAPacs002")
    void PACT_P07_aPacs002IsTaken(MockServer gateway) {
        assertThat(client(gateway).post(pacs002())).isEqualTo(202);
    }

    @Test
    @PactTestFor(pactMethod = "takesASchemaInvalidPacs008WithAReadableTxId")
    void PACT_P07_aSchemaInvalidPacs008WithAReadableTxIdIsTaken(MockServer gateway) {
        assertThat(client(gateway).post(schemaInvalidPacs008())).isEqualTo(202);
    }

    @Test
    @PactTestFor(pactMethod = "refusesAMessageWithoutAReadableTxId")
    void PACT_P07_aMessageWithoutAReadableTxIdIsRefused(MockServer gateway) {
        assertThat(client(gateway).post(NOT_AN_ENVELOPE)).isEqualTo(400);
    }

    private static V4Pact answered(PactDslWithProvider builder, String description, String envelope, int status) {
        return builder
                .uponReceiving(description)
                .path("/csm/v1/inbound").method("POST")
                .headers(Map.of("Content-Type", "application/xml;charset=UTF-8"))
                .body(envelope, "application/xml;charset=UTF-8")
                .willRespondWith()
                .status(status)
                .toPact(V4Pact.class);
    }

    private static HttpGatewayClient client(MockServer gateway) {
        return new HttpGatewayClient(RestClient.builder(), URI.create(gateway.getUrl()));
    }
}
