package com.tpe.csmgateway;

import static com.tpe.csmgateway.Commands.OWN_BIC;
import static com.tpe.csmgateway.Commands.sendCreditTransfer;
import static org.awaitility.Awaitility.await;

import au.com.dius.pact.provider.MessageAndMetadata;
import au.com.dius.pact.provider.PactVerifyProvider;
import au.com.dius.pact.provider.junit5.MessageTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.spring.junit5.PactVerificationSpringProvider;
import com.tpe.contracts.SendCreditTransfer;
import com.tpe.iso20022model.CsmMessageType;
import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.Pacs002Builder;
import com.tpe.iso20022model.Pacs002Builder.Status;
import com.tpe.iso20022model.Pacs002Builder.StatusReport;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.testsupport.pact.PactVerification;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * csm-gateway verifies every collected pact that names it (specs/20-testing-strategy.md §10.3): M-02, the outgoing
 * {@code CreditTransferSettled}, {@code CreditTransferRejected} and {@code CreditTransferNotSent} the orchestrator
 * reads from {@code csm.inbound.events}. Runs only with {@code -Ppact-verify}, against this commit's pacts and the last
 * release's ({@link PactVerification}).
 *
 * <p>An event is produced through the real path: a {@code SendCreditTransfer} on {@code csm.outbound.commands}, sent to
 * the CSM (WireMock) and answered by a {@code pacs.002} on the CSM transport, or, past its {@code sendBy}, never sent;
 * the verified bytes are the payload and headers of the outbox row the relay publishes.
 */
@Provider("csm-gateway")
@PactVerification
class CsmGatewayPactVerificationIT extends GatewayStack {

    private static final Duration HANDLED = Duration.ofSeconds(15);
    private static final Bic CSM_BIC = new Bic("TIPSXXXXXXX");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger TRANSFERS = new AtomicInteger();

    private final EnvelopeCodec envelopes = new EnvelopeCodec();

    @BeforeEach
    void target(PactVerificationContext context) {
        context.setTarget(new MessageTestTarget());
    }

    @TestTemplate
    @ExtendWith(PactVerificationSpringProvider.class)
    void PACT_M02_verify(PactVerificationContext context) {
        context.verifyInteraction();
    }

    @PactVerifyProvider("CreditTransferSettled: an outgoing transfer settled by the CSM")
    MessageAndMetadata creditTransferSettled() {
        return reported(Status.SETTLED, "CreditTransferSettled");
    }

    @PactVerifyProvider("CreditTransferRejected: an outgoing transfer rejected by the CSM")
    MessageAndMetadata creditTransferRejected() {
        return reported(Status.rejected(ReasonCode.AC04), "CreditTransferRejected");
    }

    /** A command that arrives at its {@code sendBy}: nothing goes to the CSM (15 §6.3, GW-AC-02). */
    @PactVerifyProvider("CreditTransferNotSent: an outgoing transfer never sent")
    MessageAndMetadata creditTransferNotSent() {
        String txId = txId();
        publishCommand(txId, sendCreditTransfer(txId, START.minusSeconds(10)), UUID.randomUUID());
        return published(txId, "CreditTransferNotSent");
    }

    /** A transfer sent to the CSM and answered by a {@code pacs.002} of {@code status}: the event it publishes. */
    private MessageAndMetadata reported(Status status, String type) {
        String txId = txId();
        SendCreditTransfer command = sendCreditTransfer(txId, START.minusSeconds(1));
        publishCommand(txId, command, UUID.randomUUID());
        await().atMost(HANDLED).until(() -> "DELIVERED".equals(transfer(txId).get("transmit_status")));
        String msgId = (String) transfer(txId).get("msg_id");
        inbound(envelopes.join(new Pacs002Builder().build(new StatusReport(
                new MsgId("SIMM-PACT-" + UUID.randomUUID().toString().substring(0, 8)), START, new MsgId(msgId),
                CsmMessageType.PACS_008, command.txId(), command.endToEndId(), command.uetr(), OWN_BIC, status,
                CSM_BIC, OWN_BIC))));
        return published(txId, type);
    }

    /** The outbox row of {@code type} for the transaction once written: what the relay publishes, bytes and headers. */
    private MessageAndMetadata published(String txId, String type) {
        String select = """
                SELECT payload::text, headers::text FROM outbox
                 WHERE topic = 'csm.inbound.events' AND message_key = ? AND message_type = ?
                 ORDER BY id DESC LIMIT 1""";
        await().atMost(HANDLED).until(() -> !jdbc.sql(select).param(txId).param(type).query().listOfRows().isEmpty());
        return jdbc.sql(select).param(txId).param(type)
                .query((rs, row) -> new MessageAndMetadata(rs.getString(1).getBytes(StandardCharsets.UTF_8),
                        JSON.readValue(rs.getString(2), new TypeReference<Map<String, Object>>() {})))
                .single();
    }

    private static String txId() {
        return "TX-PACT-M02-" + TRANSFERS.incrementAndGet();
    }
}
