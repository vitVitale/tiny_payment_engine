package com.tpe.csmgateway.api.commands;

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
import com.tpe.contracts.Direction;
import com.tpe.contracts.AcceptCreditTransfer;
import com.tpe.contracts.RejectCreditTransfer;
import com.tpe.contracts.RequestPaymentStatus;
import com.tpe.contracts.SendCreditTransfer;
import com.tpe.csmgateway.application.answer.AnswerCoordinator;
import com.tpe.csmgateway.application.investigation.PaymentStatusRequesting;
import com.tpe.csmgateway.application.outgoing.CreditTransferSending;
import com.tpe.paymentdomain.Money;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

/**
 * M-01, consumer csm-gateway (specs/20-testing-strategy.md §10.1, §10.2): the {@code SendCreditTransfer} of
 * payment-orchestrator on {@code csm.outbound.commands}, through the real listener and {@code ContractValidator} up
 * to the sending service, which is mocked. The body pins the fields the gateway reads and those the contract requires;
 * the times keep {@code acceptedAt < sendBy < deadlineAt}. Also the verdicts of an incoming transfer,
 * {@code AcceptCreditTransfer} and {@code RejectCreditTransfer}, up to the answer coordinator, likewise mocked, and
 * {@code RequestPaymentStatus} of an outgoing and an incoming transfer.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = PaymentOrchestratorPactTest.PROVIDER, providerType = ProviderType.ASYNCH,
        pactVersion = PactSpecVersion.V4)
class PaymentOrchestratorPactTest {

    static final String CONSUMER = "csm-gateway";
    static final String PROVIDER = "payment-orchestrator";

    private static final UUID EVENT = UUID.fromString("0192f0e4-6b1d-7c3a-9a55-4c1f3b2e9d21");
    private static final UUID PAYMENT = UUID.fromString("0192f0e4-6b1d-7c3a-9a55-4c1f3b2e9d10");
    private static final String TX_ID = "TPE01JAB3C4D5E6F7G8H9J0K1M2NP";
    private static final String INCOMING_TX_ID = "PEER20261021000017";
    /** A TxId: 1 to 35 characters of the ISO 20022 Max35Text the gateway takes. */
    private static final String TX_ID_PATTERN = "[A-Za-z0-9]{1,35}";
    private static final String IBAN_PATTERN = "[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}";
    private static final String BIC_PATTERN = "[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?";
    private static final String TIMESTAMP_PATTERN = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z";

    private final CreditTransferSending sending = mock(CreditTransferSending.class);
    private final PaymentStatusRequesting statusRequesting = mock(PaymentStatusRequesting.class);
    private final AnswerCoordinator answers = mock(AnswerCoordinator.class);
    private final CsmCommandListener listener = new CsmCommandListener(sending, statusRequesting, answers);

    @Pact(consumer = CONSUMER)
    V4Pact sendCreditTransfer(MessagePactBuilder builder) {
        return builder
                .expectsToReceive("SendCreditTransfer: an outgoing transfer to send")
                .withMetadata(metadata -> metadata
                        .add("tpe-message-type", "SendCreditTransfer")
                        .add("tpe-schema-version", "1")
                        .uuid("tpe-event-id", EVENT))
                .withContent(newJsonBody(command -> {
                    command.uuid("paymentId", PAYMENT);
                    command.stringType("txId", TX_ID);
                    command.uuid("uetr", UUID.fromString("8a562c67-ca16-48ba-b074-65581be6f001"));
                    command.stringType("endToEndId", "INV-2026-0042");
                    command.stringMatcher("acceptedAt", TIMESTAMP_PATTERN, "2026-10-21T09:15:30.000Z");
                    command.stringMatcher("sendBy", TIMESTAMP_PATTERN, "2026-10-21T09:15:40.000Z");
                    command.stringMatcher("deadlineAt", TIMESTAMP_PATTERN, "2026-10-21T09:15:50.000Z");
                    command.integerType("amountMinor", 12345);
                    command.stringValue("currency", "EUR");
                    command.object("debtor", debtor -> {
                        debtor.stringType("name", "Anna Schmidt");
                        debtor.stringMatcher("iban", IBAN_PATTERN, "DE36100100100000000001");
                        debtor.uuid("accountId", UUID.fromString("0192f0e4-6b1d-7c3a-9a55-4c1f3b2e9d30"));
                    });
                    command.stringMatcher("debtorAgentBic", BIC_PATTERN, "TPEBDEFFXXX");
                    command.object("creditor", creditor -> {
                        creditor.stringType("name", "Max Mustermann");
                        creditor.stringMatcher("iban", IBAN_PATTERN, "DE07200200200123456789");
                    });
                    command.stringMatcher("creditorAgentBic", BIC_PATTERN, "PEERDEFFXXX");
                    command.stringType("remittanceInformation", "Invoice 42");
                }).build())
                .toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact requestPaymentStatus(MessagePactBuilder builder) {
        return builder
                .expectsToReceive("RequestPaymentStatus: the status of an outgoing transfer")
                .withMetadata(metadata -> metadata
                        .add("tpe-message-type", "RequestPaymentStatus")
                        .add("tpe-schema-version", "1")
                        .uuid("tpe-event-id", EVENT))
                .withContent(newJsonBody(command -> {
                    command.uuid("paymentId", PAYMENT);
                    command.stringType("txId", TX_ID);
                    command.stringMatcher("debtorAgentBic", BIC_PATTERN, "TPEBDEFFXXX");
                    command.stringValue("direction", "OUTGOING");
                    command.integerType("attempt", 2);
                    command.stringMatcher("requestedAt", TIMESTAMP_PATTERN, "2026-10-21T09:16:20.000Z");
                }).build())
                .toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact acceptCreditTransfer(MessagePactBuilder builder) {
        return verdict(builder, "AcceptCreditTransfer: accept an incoming transfer", "AcceptCreditTransfer",
                command -> { });
    }

    @Pact(consumer = CONSUMER)
    V4Pact rejectCreditTransfer(MessagePactBuilder builder) {
        return verdict(builder, "RejectCreditTransfer: reject an incoming transfer", "RejectCreditTransfer",
                command -> command.stringMatcher("reasonCode", "[A-Z0-9]{4}", "AC01"));
    }

    @Test
    @PactTestFor(pactMethod = "acceptCreditTransfer")
    void PACT_M01_acceptCreditTransfer(V4Interaction.AsynchronousMessage message) {
        listener.on(record(message));

        var verdict = ArgumentCaptor.forClass(AcceptCreditTransfer.class);
        verify(answers).accept(eq(EVENT), verdict.capture());
        assertThat(verdict.getValue().paymentId().value()).isEqualTo(PAYMENT);
        assertThat(verdict.getValue().txId().value()).isEqualTo(INCOMING_TX_ID);
        assertThat(verdict.getValue().debtorAgentBic().value()).isEqualTo("PEERDEFFXXX");
    }

    @Test
    @PactTestFor(pactMethod = "rejectCreditTransfer")
    void PACT_M01_rejectCreditTransfer(V4Interaction.AsynchronousMessage message) {
        listener.on(record(message));

        var verdict = ArgumentCaptor.forClass(RejectCreditTransfer.class);
        verify(answers).reject(eq(EVENT), verdict.capture());
        assertThat(verdict.getValue().txId().value()).isEqualTo(INCOMING_TX_ID);
        assertThat(verdict.getValue().debtorAgentBic().value()).isEqualTo("PEERDEFFXXX");
        assertThat(verdict.getValue().reasonCode().code()).isEqualTo("AC01");
    }

    /** A verdict on an incoming transfer from the peer bank, with what {@code body} adds. */
    private static V4Pact verdict(MessagePactBuilder builder, String description, String type,
            Consumer<LambdaDslObject> body) {
        return builder
                .expectsToReceive(description)
                .withMetadata(metadata -> metadata
                        .add("tpe-message-type", type)
                        .add("tpe-schema-version", "1")
                        .uuid("tpe-event-id", EVENT))
                .withContent(newJsonBody(command -> {
                    command.uuid("paymentId", PAYMENT);
                    command.stringMatcher("txId", TX_ID_PATTERN, INCOMING_TX_ID);
                    command.stringMatcher("debtorAgentBic", BIC_PATTERN, "PEERDEFFXXX");
                    command.stringMatcher("decidedAt", TIMESTAMP_PATTERN, "2026-10-21T09:16:00.600Z");
                    body.accept(command);
                }).build())
                .toPact(V4Pact.class);
    }

    /** An accepted incoming transfer whose settlement is overdue (UC-06 8b): the debtor agent is the peer bank. */
    @Pact(consumer = CONSUMER)
    V4Pact requestIncomingPaymentStatus(MessagePactBuilder builder) {
        return builder
                .expectsToReceive("RequestPaymentStatus: the status of an incoming transfer")
                .withMetadata(metadata -> metadata
                        .add("tpe-message-type", "RequestPaymentStatus")
                        .add("tpe-schema-version", "1")
                        .uuid("tpe-event-id", EVENT))
                .withContent(newJsonBody(command -> {
                    command.uuid("paymentId", PAYMENT);
                    command.stringType("txId", "PEER20261021000017");
                    command.stringMatcher("debtorAgentBic", BIC_PATTERN, "PEERDEFFXXX");
                    command.stringValue("direction", "INCOMING");
                    command.integerType("attempt", 1);
                    command.stringMatcher("requestedAt", TIMESTAMP_PATTERN, "2026-10-21T09:16:31.000Z");
                }).build())
                .toPact(V4Pact.class);
    }

    @Test
    @PactTestFor(pactMethod = "requestIncomingPaymentStatus")
    void PACT_M01_requestIncomingPaymentStatus(V4Interaction.AsynchronousMessage message) {
        listener.on(record(message));

        var command = ArgumentCaptor.forClass(RequestPaymentStatus.class);
        verify(statusRequesting).request(eq(EVENT), command.capture());
        assertThat(command.getValue().txId().value()).isEqualTo("PEER20261021000017");
        assertThat(command.getValue().debtorAgentBic().value()).isEqualTo("PEERDEFFXXX");
        assertThat(command.getValue().direction()).isEqualTo(Direction.INCOMING);
        assertThat(command.getValue().attempt()).isEqualTo(1);
    }

    @Test
    @PactTestFor(pactMethod = "requestPaymentStatus")
    void PACT_M01_requestPaymentStatus(V4Interaction.AsynchronousMessage message) {
        listener.on(record(message));

        var command = ArgumentCaptor.forClass(RequestPaymentStatus.class);
        verify(statusRequesting).request(eq(EVENT), command.capture());
        assertThat(command.getValue().paymentId().value()).isEqualTo(PAYMENT);
        assertThat(command.getValue().txId().value()).isEqualTo(TX_ID);
        assertThat(command.getValue().debtorAgentBic().value()).isEqualTo("TPEBDEFFXXX");
        assertThat(command.getValue().direction()).isEqualTo(Direction.OUTGOING);
        assertThat(command.getValue().attempt()).isEqualTo(2);
        assertThat(command.getValue().requestedAt()).isEqualTo(Instant.parse("2026-10-21T09:16:20.000Z"));
    }

    @Test
    @PactTestFor(pactMethod = "sendCreditTransfer")
    void PACT_M01_sendCreditTransfer(V4Interaction.AsynchronousMessage message) {
        listener.on(record(message));

        var command = ArgumentCaptor.forClass(SendCreditTransfer.class);
        verify(sending).send(eq(EVENT), command.capture());
        assertThat(command.getValue().paymentId().value()).isEqualTo(PAYMENT);
        assertThat(command.getValue().txId().value()).isEqualTo(TX_ID);
        assertThat(command.getValue().sendBy()).isEqualTo(Instant.parse("2026-10-21T09:15:40.000Z"));
        assertThat(command.getValue().amount()).isEqualTo(Money.parse("123.45"));
        assertThat(command.getValue().debtorAgentBic().value()).isEqualTo("TPEBDEFFXXX");
        assertThat(command.getValue().creditor().iban().value()).isEqualTo("DE07200200200123456789");
    }

    private static ConsumerRecord<String, String> record(V4Interaction.AsynchronousMessage message) {
        var record = new ConsumerRecord<>(CreditTransferSending.COMMANDS, 0, 0L, TX_ID,
                message.getContents().getContents().valueAsString());
        message.getContents().getMetadata().forEach((name, value) ->
                record.headers().add(name, String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        return record;
    }
}
