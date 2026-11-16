package com.tpe.csmgateway.infrastructure.csm;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.tpe.csmgateway.application.transmit.CsmClient.CsmUnavailableException;
import com.tpe.iso20022model.CsmMessageType;
import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.Pacs002Builder;
import com.tpe.iso20022model.Pacs002Builder.Status;
import com.tpe.iso20022model.Pacs002Builder.StatusReport;
import com.tpe.iso20022model.Pacs008Builder;
import com.tpe.iso20022model.Pacs008Builder.AccountHolder;
import com.tpe.iso20022model.Pacs008Builder.CreditTransfer;
import com.tpe.iso20022model.Pacs028Builder;
import com.tpe.iso20022model.Pacs028Builder.StatusRequest;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.RemittanceInformation;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.web.client.RestClient;

/**
 * P-06, consumer csm-gateway (specs/20-testing-strategy.md §10.1, §10.2): {@code POST /csm/v1/messages} through the
 * real {@link HttpCsmClient}. The pact covers the envelope and the transport answer (§10, XML bodies): {@code 202} for
 * a {@code pacs.008}, {@code pacs.002} and {@code pacs.028}, {@code 400} for an unparsable or a schema-invalid
 * envelope. The documents are
 * built as the gateway builds them; their content is the XSDs' job.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = CsmSimulatorPactTest.PROVIDER, pactVersion = PactSpecVersion.V4)
class CsmSimulatorPactTest {

    static final String CONSUMER = "csm-gateway";
    static final String PROVIDER = "csm-simulator";

    private static final Instant AT = Instant.parse("2026-11-15T10:00:00.123Z");
    private static final Bic OWN = new Bic("TPEBDEFFXXX");
    private static final Bic PEER = new Bic("PEERDEFFXXX");
    private static final MsgId PACS_008_MSG_ID = new MsgId("TPEM01JCSMPACT0000000000000008");
    private static final TxId TX_ID = new TxId("TPE01JCSMPACT00000000000000TX");
    private static final EndToEndId END_TO_END_ID = new EndToEndId("INV-2026-0042");
    private static final Uetr UETR = new Uetr("8a562c67-ca16-48ba-b074-65581be6f001");

    /** Well-formed XML, but no CSM envelope: the simulator cannot split it. */
    private static final String NOT_AN_ENVELOPE = "<NotAnEnvelope xmlns=\"urn:example\"/>";

    private static final EnvelopeCodec ENVELOPES = new EnvelopeCodec();

    static String pacs008() {
        return ENVELOPES.join(new Pacs008Builder().build(new CreditTransfer(PACS_008_MSG_ID, AT, TX_ID, END_TO_END_ID,
                UETR, Money.parse("123.45"), LocalDate.parse("2026-11-15"), AT.minusMillis(23),
                new AccountHolder(new PartyName("Anna Schmidt"), new Iban("DE36100100100000000001")), OWN, PEER,
                new AccountHolder(new PartyName("Max Mustermann"), new Iban("DE07200200200123456789")),
                new RemittanceInformation("Invoice 42"))));
    }

    /** Our answer to an incoming transfer: the peer is its debtor agent. */
    static String pacs002() {
        return ENVELOPES.join(new Pacs002Builder().build(new StatusReport(new MsgId("TPEM01JCSMPACT0000000000000002"),
                AT, new MsgId("PEERM0000000000000000000000001"), CsmMessageType.PACS_008,
                new TxId("PEER0000000000000000000000TX"), END_TO_END_ID, UETR, PEER, Status.ACCEPTED, OWN, PEER)));
    }

    static String pacs028() {
        return ENVELOPES.join(new Pacs028Builder().build(new StatusRequest(new MsgId("TPEM01JCSMPACT0000000000000028"),
                AT, PACS_008_MSG_ID, TX_ID, END_TO_END_ID, UETR, OWN, OWN, PEER)));
    }

    @Pact(consumer = CONSUMER)
    V4Pact acceptsAPacs008(PactDslWithProvider builder) {
        return accepted(builder, "a pacs.008 envelope", pacs008());
    }

    @Pact(consumer = CONSUMER)
    V4Pact acceptsAPacs002(PactDslWithProvider builder) {
        return accepted(builder, "a pacs.002 envelope", pacs002());
    }

    @Pact(consumer = CONSUMER)
    V4Pact acceptsAPacs028(PactDslWithProvider builder) {
        return accepted(builder, "a pacs.028 envelope", pacs028());
    }

    @Pact(consumer = CONSUMER)
    V4Pact refusesAnUnparsableEnvelope(PactDslWithProvider builder) {
        return builder
                .uponReceiving("an unparsable envelope")
                .path("/csm/v1/messages").method("POST")
                .headers(Map.of("Content-Type", "application/xml;charset=UTF-8"))
                .body(NOT_AN_ENVELOPE, "application/xml;charset=UTF-8")
                .willRespondWith()
                .status(400)
                .toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact refusesASchemaInvalidEnvelope(PactDslWithProvider builder) {
        return builder
                .uponReceiving("a schema-invalid pacs.008 envelope")
                .path("/csm/v1/messages").method("POST")
                .headers(Map.of("Content-Type", "application/xml;charset=UTF-8"))
                .body(schemaInvalidPacs008(), "application/xml;charset=UTF-8")
                .willRespondWith()
                .status(400)
                .toPact(V4Pact.class);
    }

    /** A {@code pacs.008} without its mandatory {@code ChrgBr}: the gateway never sends one; the simulator refuses it. */
    static String schemaInvalidPacs008() {
        return pacs008().replace("<ChrgBr>SLEV</ChrgBr>", "");
    }

    @Test
    @PactTestFor(pactMethod = "acceptsAPacs008")
    void PACT_P06_aPacs008IsTaken(MockServer csm) {
        assertThatCode(() -> client(csm).send(pacs008())).doesNotThrowAnyException();
    }

    @Test
    @PactTestFor(pactMethod = "acceptsAPacs002")
    void PACT_P06_aPacs002IsTaken(MockServer csm) {
        assertThatCode(() -> client(csm).send(pacs002())).doesNotThrowAnyException();
    }

    @Test
    @PactTestFor(pactMethod = "acceptsAPacs028")
    void PACT_P06_aPacs028IsTaken(MockServer csm) {
        assertThatCode(() -> client(csm).send(pacs028())).doesNotThrowAnyException();
    }

    @Test
    @PactTestFor(pactMethod = "refusesAnUnparsableEnvelope")
    void PACT_P06_anUnparsableEnvelopeIsRefused(MockServer csm) {
        assertThatThrownBy(() -> client(csm).send(NOT_AN_ENVELOPE)).isInstanceOf(CsmUnavailableException.class);
    }

    @Test
    @PactTestFor(pactMethod = "refusesASchemaInvalidEnvelope")
    void PACT_P06_aSchemaInvalidEnvelopeIsRefused(MockServer csm) {
        assertThatThrownBy(() -> client(csm).send(schemaInvalidPacs008())).isInstanceOf(CsmUnavailableException.class)
                .hasMessage("HTTP 400");
    }

    private static V4Pact accepted(PactDslWithProvider builder, String description, String envelope) {
        return builder
                .uponReceiving(description)
                .path("/csm/v1/messages").method("POST")
                .headers(Map.of("Content-Type", "application/xml;charset=UTF-8"))
                .body(envelope, "application/xml;charset=UTF-8")
                .willRespondWith()
                .status(202)
                .toPact(V4Pact.class);
    }

    private static HttpCsmClient client(MockServer csm) {
        return new HttpCsmClient(RestClient.builder(), URI.create(csm.getUrl()), Duration.ofSeconds(1),
                Duration.ofSeconds(2));
    }
}
