package com.tpe.csmgateway.api.commands;

import static au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import au.com.dius.pact.consumer.MessagePactBuilder;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.consumer.junit5.ProviderType;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Interaction;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.tpe.contracts.Direction;
import com.tpe.contracts.RequestPaymentStatus;
import com.tpe.contracts.SendCreditTransfer;
import com.tpe.csmgateway.application.answer.AnswerCoordinator;
import com.tpe.csmgateway.application.investigation.PaymentStatusRequesting;
import com.tpe.csmgateway.application.outgoing.CreditTransferSending;
import com.tpe.paymentdomain.Money;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

/**
 * M-01, consumer csm-gateway (specs/20-testing-strategy.md §10.1, §10.2): the {@code SendCreditTransfer} of
 * payment-orchestrator on {@code csm.outbound.commands}, through the real listener and {@code ContractValidator} up
 * to the sending service, which is mocked. The body pins the fields the gateway reads and those the contract requires;
 * the times keep {@code acceptedAt < sendBy < deadlineAt}. {@code AcceptCreditTransfer} and
 * {@code RejectCreditTransfer}, which the gateway consumes already, join once payment-orchestrator produces them
 * (its incoming flow): a pact interaction its provider verification cannot produce would fail that verification.
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
    private static final String IBAN_PATTERN = "[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}";
    private static final String BIC_PATTERN = "[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?";
    private static final String TIMESTAMP_PATTERN = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z";

    private final CreditTransferSending sending = mock(CreditTransferSending.class);
    private final PaymentStatusRequesting statusRequesting = mock(PaymentStatusRequesting.class);
    private final CsmCommandListener listener = new CsmCommandListener(sending, statusRequesting,
            mock(AnswerCoordinator.class));

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
