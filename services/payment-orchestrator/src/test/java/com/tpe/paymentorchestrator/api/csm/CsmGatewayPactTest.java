package com.tpe.paymentorchestrator.api.csm;

import static au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import au.com.dius.pact.consumer.MessagePactBuilder;
import au.com.dius.pact.consumer.dsl.LambdaDslObject;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.consumer.junit5.ProviderType;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Interaction;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.tpe.contracts.AnswerSent;
import com.tpe.contracts.AnswerStatus;
import com.tpe.contracts.CreditTransferNotSent;
import com.tpe.contracts.CreditTransferReceived;
import com.tpe.contracts.DecidedBy;
import com.tpe.contracts.CreditTransferRejected;
import com.tpe.contracts.CreditTransferSettled;
import com.tpe.contracts.Direction;
import com.tpe.contracts.Message;
import com.tpe.contracts.SettlementSource;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentorchestrator.application.csm.CsmEventHandling;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

/**
 * M-02, consumer payment-orchestrator (specs/20-testing-strategy.md §10.1, §10.2): the outgoing events of csm-gateway
 * on {@code csm.inbound.events}, through the real listener and {@code ContractValidator} up to the handling, which
 * is mocked. The bodies pin the fields the orchestrator reads and those the contracts require. The answers of an
 * investigation ({@code source = INVESTIGATION}: settled, or unknown after the deadline, UC-05) are interactions of
 * their own. Of the incoming events: {@code CreditTransferReceived}, and {@code AnswerSent} accepted, rejected by our
 * verdict, and rejected {@code AB05} at the answer deadline; the incoming settlement follows with its flow.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = CsmGatewayPactTest.PROVIDER, providerType = ProviderType.ASYNCH,
        pactVersion = PactSpecVersion.V4)
class CsmGatewayPactTest {

    static final String CONSUMER = "payment-orchestrator";
    static final String PROVIDER = "csm-gateway";

    private static final UUID EVENT = UUID.fromString("0192f0e4-6b1d-7c3a-9a55-4c1f3b2e9d41");
    private static final UUID PAYMENT = UUID.fromString("0192f0e4-6b1d-7c3a-9a55-4c1f3b2e9d10");
    private static final String TX_ID = "TPE01JAB3C4D5E6F7G8H9J0K1M2NP";
    private static final String INCOMING_TX_ID = "PEER20261021000017";
    private static final String BIC_PATTERN = "[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?";
    private static final String TIMESTAMP_PATTERN = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z";

    private final CsmEventHandling handling = mock(CsmEventHandling.class);
    private final CsmEventListener listener = new CsmEventListener(handling);

    @Pact(consumer = CONSUMER)
    V4Pact creditTransferSettled(MessagePactBuilder builder) {
        return event(builder, "CreditTransferSettled: an outgoing transfer settled by the CSM",
                "CreditTransferSettled", body -> {
                    outgoing(body);
                    body.stringMatcher("settledAt", TIMESTAMP_PATTERN, "2026-10-21T09:15:31.200Z");
                    body.stringMatcher("source", "STATUS_REPORT|INVESTIGATION", "STATUS_REPORT");
                });
    }

    @Pact(consumer = CONSUMER)
    V4Pact creditTransferRejected(MessagePactBuilder builder) {
        return event(builder, "CreditTransferRejected: an outgoing transfer rejected by the CSM",
                "CreditTransferRejected", body -> {
                    outgoing(body);
                    body.stringMatcher("reasonCode", "[A-Z0-9]{4}", "AC04");
                    body.stringMatcher("rejectedAt", TIMESTAMP_PATTERN, "2026-10-21T09:15:31.200Z");
                    body.stringMatcher("source", "STATUS_REPORT|INVESTIGATION", "STATUS_REPORT");
                });
    }

    /** A {@code pacs.028} answered {@code ACSC} (UC-05 3, E2E-O-09). */
    @Pact(consumer = CONSUMER)
    V4Pact creditTransferSettledByInvestigation(MessagePactBuilder builder) {
        return event(builder, "CreditTransferSettled: an outgoing transfer an investigation found settled",
                "CreditTransferSettled", body -> {
                    outgoing(body);
                    body.stringMatcher("settledAt", TIMESTAMP_PATTERN, "2026-10-21T09:15:51.200Z");
                    body.stringValue("source", "INVESTIGATION");
                });
    }

    /** A {@code pacs.028} answered {@code UNKNOWN_TX} after the deadline: rejected {@code AB05} (UC-05 4c, E2E-O-10). */
    @Pact(consumer = CONSUMER)
    V4Pact creditTransferUnknownAfterTheDeadline(MessagePactBuilder builder) {
        return event(builder, "CreditTransferRejected: an outgoing transfer unknown to the CSM after the deadline",
                "CreditTransferRejected", body -> {
                    outgoing(body);
                    body.stringValue("reasonCode", "AB05");
                    body.stringMatcher("rejectedAt", TIMESTAMP_PATTERN, "2026-10-21T09:15:51.200Z");
                    body.stringValue("source", "INVESTIGATION");
                });
    }

    @Pact(consumer = CONSUMER)
    V4Pact creditTransferNotSent(MessagePactBuilder builder) {
        return event(builder, "CreditTransferNotSent: an outgoing transfer never sent", "CreditTransferNotSent",
                body -> {
                    outgoing(body);
                    body.stringMatcher("reasonCode", "AB03|FF01", "AB03");
                    body.stringMatcher("sendBy", TIMESTAMP_PATTERN, "2026-10-21T09:15:40.000Z");
                    body.stringMatcher("decidedAt", TIMESTAMP_PATTERN, "2026-10-21T09:15:41.000Z");
                });
    }

    @Pact(consumer = CONSUMER)
    V4Pact creditTransferReceived(MessagePactBuilder builder) {
        return event(builder, "CreditTransferReceived: an incoming transfer for one of our customers",
                "CreditTransferReceived", body -> {
                    incoming(body);
                    body.stringType("msgId", "PEERM20261021000017");
                    body.uuid("uetr", UUID.fromString("3f2c1b0a-9d8e-4c7b-a6f5-e4d3c2b1a090"));
                    body.stringType("endToEndId", "RENT-2026-10");
                    body.stringMatcher("receivedAt", TIMESTAMP_PATTERN, "2026-10-21T09:16:00.040Z");
                    body.stringMatcher("answerDeadline", TIMESTAMP_PATTERN, "2026-10-21T09:16:05.040Z");
                    body.stringMatcher("acceptanceDateTime", TIMESTAMP_PATTERN, "2026-10-21T09:15:59.000Z");
                    body.stringMatcher("interbankSettlementDate", "\\d{4}-\\d{2}-\\d{2}", "2026-10-21");
                    body.integerType("amountMinor", 5_000);
                    body.stringValue("currency", "EUR");
                    body.object("debtor", debtor -> {
                        debtor.stringType("name", "Max Mustermann");
                        debtor.stringType("iban", "DE07200200200123456789");
                    });
                    body.object("creditor", creditor -> {
                        creditor.stringType("name", "Anna Schmidt");
                        creditor.stringType("iban", "DE89370400440532013000");
                    });
                    body.stringMatcher("creditorAgentBic", BIC_PATTERN, "TPEBDEFFXXX");
                    body.stringType("remittanceInformation", "Rent October");
                });
    }

    @Pact(consumer = CONSUMER)
    V4Pact answerSentAccepted(MessagePactBuilder builder) {
        return event(builder, "AnswerSent: an incoming transfer accepted by our verdict", "AnswerSent", body -> {
            incoming(body);
            body.stringValue("status", "ACCP");
            body.stringValue("decidedBy", "VERDICT");
            body.stringMatcher("sentAt", TIMESTAMP_PATTERN, "2026-10-21T09:16:00.900Z");
        });
    }

    @Pact(consumer = CONSUMER)
    V4Pact answerSentRejectedByVerdict(MessagePactBuilder builder) {
        return event(builder, "AnswerSent: an incoming transfer rejected by our verdict", "AnswerSent", body -> {
            incoming(body);
            body.stringValue("status", "RJCT");
            body.stringMatcher("reasonCode", "[A-Z0-9]{4}", "AC06");
            body.stringValue("decidedBy", "VERDICT");
            body.stringMatcher("sentAt", TIMESTAMP_PATTERN, "2026-10-21T09:16:00.900Z");
        });
    }

    /** No verdict by the answer deadline: the gateway rejected it {@code AB05} by itself (UC-06 6b). */
    @Pact(consumer = CONSUMER)
    V4Pact answerSentRejectedByTheDeadline(MessagePactBuilder builder) {
        return event(builder, "AnswerSent: an incoming transfer rejected at the answer deadline", "AnswerSent",
                body -> {
                    incoming(body);
                    body.stringValue("status", "RJCT");
                    body.stringValue("reasonCode", "AB05");
                    body.stringValue("decidedBy", "DEADLINE");
                    body.stringMatcher("sentAt", TIMESTAMP_PATTERN, "2026-10-21T09:16:05.040Z");
                });
    }

    @Test
    @PactTestFor(pactMethod = "creditTransferReceived")
    void PACT_M02_creditTransferReceived(V4Interaction.AsynchronousMessage message) {
        var event = (CreditTransferReceived) handled(message);

        assertThat(event.txId().value()).isEqualTo(INCOMING_TX_ID);
        assertThat(event.debtorAgentBic().value()).isEqualTo("PEERDEFFXXX");
        assertThat(event.direction()).isEqualTo(Direction.INCOMING);
        assertThat(event.answerDeadline()).isEqualTo(Instant.parse("2026-10-21T09:16:05.040Z"));
        assertThat(event.amount().minor()).isEqualTo(5_000);
        assertThat(event.creditor().iban().value()).isEqualTo("DE89370400440532013000");
        assertThat(event.creditorAgentBic().value()).isEqualTo("TPEBDEFFXXX");
    }

    @Test
    @PactTestFor(pactMethod = "answerSentAccepted")
    void PACT_M02_answerSentAccepted(V4Interaction.AsynchronousMessage message) {
        var event = (AnswerSent) handled(message);

        assertThat(event.txId().value()).isEqualTo(INCOMING_TX_ID);
        assertThat(event.status()).isEqualTo(AnswerStatus.ACCP);
        assertThat(event.reasonCode()).isNull();
        assertThat(event.decidedBy()).isEqualTo(DecidedBy.VERDICT);
    }

    @Test
    @PactTestFor(pactMethod = "answerSentRejectedByVerdict")
    void PACT_M02_answerSentRejectedByVerdict(V4Interaction.AsynchronousMessage message) {
        var event = (AnswerSent) handled(message);

        assertThat(event.status()).isEqualTo(AnswerStatus.RJCT);
        assertThat(event.reasonCode()).isEqualTo(ReasonCode.AC06);
        assertThat(event.decidedBy()).isEqualTo(DecidedBy.VERDICT);
    }

    @Test
    @PactTestFor(pactMethod = "answerSentRejectedByTheDeadline")
    void PACT_M02_answerSentRejectedByTheDeadline(V4Interaction.AsynchronousMessage message) {
        var event = (AnswerSent) handled(message);

        assertThat(event.status()).isEqualTo(AnswerStatus.RJCT);
        assertThat(event.reasonCode()).isEqualTo(ReasonCode.AB05);
        assertThat(event.decidedBy()).isEqualTo(DecidedBy.DEADLINE);
    }

    @Test
    @PactTestFor(pactMethod = "creditTransferSettled")
    void PACT_M02_creditTransferSettled(V4Interaction.AsynchronousMessage message) {
        var event = (CreditTransferSettled) handled(message);

        assertThat(event.txId().value()).isEqualTo(TX_ID);
        assertThat(event.debtorAgentBic().value()).isEqualTo("TPEBDEFFXXX");
        assertThat(event.direction()).isEqualTo(Direction.OUTGOING);
        assertThat(event.paymentId().value()).isEqualTo(PAYMENT);
        assertThat(event.source()).isEqualTo(SettlementSource.STATUS_REPORT);
    }

    @Test
    @PactTestFor(pactMethod = "creditTransferRejected")
    void PACT_M02_creditTransferRejected(V4Interaction.AsynchronousMessage message) {
        var event = (CreditTransferRejected) handled(message);

        assertThat(event.txId().value()).isEqualTo(TX_ID);
        assertThat(event.direction()).isEqualTo(Direction.OUTGOING);
        assertThat(event.reasonCode()).isEqualTo(ReasonCode.AC04);
    }

    @Test
    @PactTestFor(pactMethod = "creditTransferSettledByInvestigation")
    void PACT_M02_creditTransferSettledByInvestigation(V4Interaction.AsynchronousMessage message) {
        var event = (CreditTransferSettled) handled(message);

        assertThat(event.txId().value()).isEqualTo(TX_ID);
        assertThat(event.direction()).isEqualTo(Direction.OUTGOING);
        assertThat(event.source()).isEqualTo(SettlementSource.INVESTIGATION);
    }

    @Test
    @PactTestFor(pactMethod = "creditTransferUnknownAfterTheDeadline")
    void PACT_M02_creditTransferUnknownAfterTheDeadline(V4Interaction.AsynchronousMessage message) {
        var event = (CreditTransferRejected) handled(message);

        assertThat(event.txId().value()).isEqualTo(TX_ID);
        assertThat(event.reasonCode()).isEqualTo(ReasonCode.AB05);
        assertThat(event.source()).isEqualTo(SettlementSource.INVESTIGATION);
    }

    @Test
    @PactTestFor(pactMethod = "creditTransferNotSent")
    void PACT_M02_creditTransferNotSent(V4Interaction.AsynchronousMessage message) {
        var event = (CreditTransferNotSent) handled(message);

        assertThat(event.txId().value()).isEqualTo(TX_ID);
        assertThat(event.reasonCode()).isEqualTo(ReasonCode.AB03);
        assertThat(event.sendBy()).isEqualTo(Instant.parse("2026-10-21T09:15:40.000Z"));
    }

    private static V4Pact event(MessagePactBuilder builder, String description, String type,
            Consumer<LambdaDslObject> body) {
        return builder
                .expectsToReceive(description)
                .withMetadata(metadata -> metadata
                        .add("tpe-message-type", type)
                        .add("tpe-schema-version", "1")
                        .uuid("tpe-event-id", EVENT))
                .withContent(newJsonBody(body::accept).build())
                .toPact(V4Pact.class);
    }

    private static void incoming(LambdaDslObject body) {
        body.stringType("txId", INCOMING_TX_ID);
        body.stringMatcher("debtorAgentBic", BIC_PATTERN, "PEERDEFFXXX");
        body.stringValue("direction", "INCOMING");
    }

    private static void outgoing(LambdaDslObject body) {
        body.stringType("txId", TX_ID);
        body.stringMatcher("debtorAgentBic", BIC_PATTERN, "TPEBDEFFXXX");
        body.stringValue("direction", "OUTGOING");
        body.uuid("paymentId", PAYMENT);
    }

    /** Through the listener: what reached the handling, under the event's id. */
    private Message handled(V4Interaction.AsynchronousMessage message) {
        var record = new ConsumerRecord<>(CsmEventHandling.EVENTS, 0, 0L, TX_ID,
                message.getContents().getContents().valueAsString());
        message.getContents().getMetadata().forEach((name, value) ->
                record.headers().add(name, String.valueOf(value).getBytes(StandardCharsets.UTF_8)));

        listener.on(record);

        var event = ArgumentCaptor.forClass(Message.class);
        verify(handling).handle(eq(EVENT), event.capture());
        return event.getValue();
    }
}
