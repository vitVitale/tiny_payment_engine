package com.tpe.csmgateway;

import static com.tpe.csmgateway.Commands.OWN_BIC;
import static com.tpe.csmgateway.Commands.requestPaymentStatus;
import static com.tpe.csmgateway.Commands.sendCreditTransfer;
import static org.awaitility.Awaitility.await;

import au.com.dius.pact.provider.MessageAndMetadata;
import au.com.dius.pact.provider.PactVerifyProvider;
import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.MessageTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.spring.junit5.PactVerificationSpringProvider;
import com.tpe.contracts.AcceptCreditTransfer;
import com.tpe.contracts.Direction;
import com.tpe.contracts.RejectCreditTransfer;
import com.tpe.contracts.RequestPaymentStatus;
import com.tpe.contracts.SendCreditTransfer;
import com.tpe.iso20022model.CsmMessageType;
import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.Pacs002Builder;
import com.tpe.iso20022model.Pacs002Builder.Status;
import com.tpe.iso20022model.Pacs002Builder.StatusReport;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.TxId;
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
 * reads from {@code csm.inbound.events}, from a status report and from an investigation's answer, and the incoming
 * {@code CreditTransferReceived} and {@code AnswerSent} (by verdict, and at the answer deadline), and an accepted
 * incoming transfer's {@code CreditTransferSettled} and {@code CreditTransferRejected} (status report, investigation);
 * and P-07, the CSM
 * inbound endpoint the simulator posts to, whose interactions need no state: every message is taken or refused on
 * what it is alone. Runs only with {@code -Ppact-verify}, against this commit's pacts and the last release's
 * ({@link PactVerification}).
 *
 * <p>An event is produced through the real path: a {@code SendCreditTransfer} on {@code csm.outbound.commands}, sent to
 * the CSM (WireMock) and answered by a {@code pacs.002} on the CSM transport, or, past its {@code sendBy}, never sent;
 * for an investigation a {@code RequestPaymentStatus} follows and its {@code pacs.028} is answered; an incoming
 * {@code pacs.008} on the CSM transport, then a verdict on {@code csm.outbound.commands} or the clock past the answer
 * deadline;
 * the verified bytes are the payload and headers of the outbox row the relay publishes.
 */
@Provider("csm-gateway")
@PactVerification
class CsmGatewayPactVerificationIT extends GatewayStack {

    private static final Duration HANDLED = Duration.ofSeconds(15);
    private static final Bic CSM_BIC = new Bic("TIPSXXXXXXX");
    private static final Bic PEER_BIC = IncomingPacs008.PEER_BIC;
    /** {@code tpe.csm.answer-deadline-offset}. */
    private static final Duration ANSWER_DEADLINE_OFFSET = Duration.ofSeconds(5);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger TRANSFERS = new AtomicInteger();

    private final EnvelopeCodec envelopes = new EnvelopeCodec();

    @BeforeEach
    void target(PactVerificationContext context) {
        context.setTarget(context.getInteraction().isAsynchronousMessage()
                ? new MessageTestTarget()
                : new HttpTestTarget("localhost", port));
    }

    @TestTemplate
    @ExtendWith(PactVerificationSpringProvider.class)
    void PACT_M02_PACT_P07_verify(PactVerificationContext context) {
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

    /** A {@code pacs.028} answered {@code ACSC} (15 §6.6, GW-AC-06). */
    @PactVerifyProvider("CreditTransferSettled: an outgoing transfer an investigation found settled")
    MessageAndMetadata creditTransferSettledByInvestigation() {
        Investigated investigated = investigated();
        inbound(answer(investigated.command(), investigated.requestMsgId(), CsmMessageType.PACS_028, Status.SETTLED));
        return published(investigated.command().txId().value(), "CreditTransferSettled");
    }

    /** A {@code pacs.028} answered {@code UNKNOWN_TX} once the deadline has passed (15 §6.6, GW-AC-07). */
    @PactVerifyProvider("CreditTransferRejected: an outgoing transfer unknown to the CSM after the deadline")
    MessageAndMetadata creditTransferUnknownAfterTheDeadline() {
        Investigated investigated = investigated();
        CLOCK.set(investigated.command().deadlineAt());
        inbound(answer(investigated.command(), investigated.requestMsgId(), CsmMessageType.PACS_028,
                Status.UNKNOWN_TRANSACTION));
        return published(investigated.command().txId().value(), "CreditTransferRejected");
    }

    /** A command that arrives at its {@code sendBy}: nothing goes to the CSM (15 §6.3, GW-AC-02). */
    @PactVerifyProvider("CreditTransferNotSent: an outgoing transfer never sent")
    MessageAndMetadata creditTransferNotSent() {
        String txId = txId();
        publishCommand(txId, sendCreditTransfer(txId, START.minusSeconds(10)), UUID.randomUUID());
        return published(txId, "CreditTransferNotSent");
    }

    /** A valid incoming {@code pacs.008} from the peer bank (15 §6.2, GW-AC-09). */
    @PactVerifyProvider("CreditTransferReceived: an incoming transfer for one of our customers")
    MessageAndMetadata creditTransferReceived() {
        return published(received(), "CreditTransferReceived");
    }

    /** The orchestrator's {@code AcceptCreditTransfer} before the answer deadline (15 §6.3, GW-AC-11). */
    @PactVerifyProvider("AnswerSent: an incoming transfer accepted by our verdict")
    MessageAndMetadata answerSentAccepted() {
        String txId = received();
        publishCommand(txId, new AcceptCreditTransfer(new PaymentId(UUID.randomUUID()), new TxId(txId), PEER_BIC,
                CLOCK.instant()), UUID.randomUUID());
        return published(txId, "AnswerSent");
    }

    /** The orchestrator's {@code RejectCreditTransfer AC06} before the answer deadline (15 §6.3). */
    @PactVerifyProvider("AnswerSent: an incoming transfer rejected by our verdict")
    MessageAndMetadata answerSentRejectedByVerdict() {
        String txId = received();
        publishCommand(txId, new RejectCreditTransfer(new PaymentId(UUID.randomUUID()), new TxId(txId), PEER_BIC,
                CLOCK.instant(), ReasonCode.AC06), UUID.randomUUID());
        return published(txId, "AnswerSent");
    }

    /** No verdict by the answer deadline: the deadline worker answers {@code RJCT AB05} (15 §6.3, GW-AC-11). */
    @PactVerifyProvider("AnswerSent: an incoming transfer rejected at the answer deadline")
    MessageAndMetadata answerSentRejectedByTheDeadline() {
        String txId = received();
        CLOCK.set(CLOCK.instant().plus(ANSWER_DEADLINE_OFFSET));
        return published(txId, "AnswerSent");
    }

    /** The CSM's status report settling an accepted incoming transfer (15 §6.4, UC-06 9). */
    @PactVerifyProvider("CreditTransferSettled: an incoming transfer settled by the CSM")
    MessageAndMetadata incomingSettled() {
        String txId = accepted();
        inbound(incomingReport(txId, new MsgId("M-PACT-" + txId), CsmMessageType.PACS_008, Status.SETTLED));
        return published(txId, "CreditTransferSettled");
    }

    /** The CSM's status report rejecting an incoming transfer after our {@code ACCP} (15 §6.4, UC-06 8a). */
    @PactVerifyProvider("CreditTransferRejected: an incoming transfer rejected by the CSM after our ACCP")
    MessageAndMetadata incomingRejected() {
        String txId = accepted();
        inbound(incomingReport(txId, new MsgId("M-PACT-" + txId), CsmMessageType.PACS_008,
                Status.rejected(ReasonCode.AB05)));
        return published(txId, "CreditTransferRejected");
    }

    /** A {@code pacs.028} of an accepted incoming transfer answered {@code ACSC} (15 §6.6, UC-06 8b). */
    @PactVerifyProvider("CreditTransferSettled: an incoming transfer an investigation found settled")
    MessageAndMetadata incomingSettledByInvestigation() {
        String txId = accepted();
        inbound(incomingReport(txId, incomingStatusRequested(txId), CsmMessageType.PACS_028, Status.SETTLED));
        return published(txId, "CreditTransferSettled");
    }

    /** A {@code pacs.028} of an accepted incoming transfer answered rejected (15 §6.6, UC-06 8b). */
    @PactVerifyProvider("CreditTransferRejected: an incoming transfer an investigation found rejected")
    MessageAndMetadata incomingRejectedByInvestigation() {
        String txId = accepted();
        inbound(incomingReport(txId, incomingStatusRequested(txId), CsmMessageType.PACS_028,
                Status.rejected(ReasonCode.AB05)));
        return published(txId, "CreditTransferRejected");
    }

    /** An incoming transfer received and accepted: its {@code ACCP} delivered to the CSM. */
    private String accepted() {
        String txId = received();
        publishCommand(txId, new AcceptCreditTransfer(new PaymentId(UUID.randomUUID()), new TxId(txId), PEER_BIC,
                CLOCK.instant()), UUID.randomUUID());
        String select = """
                SELECT transmit_status FROM incoming_transfers WHERE debtor_agent_bic = ? AND tx_id = ?""";
        await().atMost(HANDLED).until(() -> jdbc.sql(select).param(PEER_BIC.value()).param(txId)
                .query(String.class).optional().filter("DELIVERED"::equals).isPresent());
        return txId;
    }

    /** The orchestrator asks the status of the incoming transfer (attempt 1): its {@code pacs.028}, delivered. */
    private MsgId incomingStatusRequested(String txId) {
        publishCommand(txId, new RequestPaymentStatus(new PaymentId(UUID.randomUUID()), new TxId(txId), PEER_BIC,
                Direction.INCOMING, 1, CLOCK.instant()), UUID.randomUUID());
        String select = """
                SELECT msg_id FROM status_requests
                 WHERE tx_id = ? AND debtor_agent_bic = ? AND attempt = 1 AND transmit_status = 'DELIVERED'""";
        await().atMost(HANDLED).until(() -> !jdbc.sql(select).param(txId).param(PEER_BIC.value())
                .query(String.class).list().isEmpty());
        return new MsgId(jdbc.sql(select).param(txId).param(PEER_BIC.value()).query(String.class).single());
    }

    /** The CSM's {@code pacs.002} of the incoming transfer, answering our message {@code original}. */
    private String incomingReport(String txId, MsgId original, CsmMessageType originalType, Status status) {
        return envelopes.join(new Pacs002Builder().build(new StatusReport(
                new MsgId("SIMM-PACT-" + UUID.randomUUID().toString().substring(0, 8)), CLOCK.instant(), original,
                originalType, new TxId(txId), new EndToEndId("E2E-" + txId), IncomingPacs008.uetr(txId), PEER_BIC,
                status, CSM_BIC, OWN_BIC)));
    }

    /** An incoming transfer the CSM delivered now: its {@code TxId}. */
    private String received() {
        String txId = txId();
        inbound(IncomingPacs008.pacs008("M-PACT-" + txId, txId, PEER_BIC, CLOCK.instant()));
        return txId;
    }

    /** A transfer sent to the CSM and answered by a {@code pacs.002} of {@code status}: the event it publishes. */
    private MessageAndMetadata reported(Status status, String type) {
        SendCreditTransfer command = sent();
        inbound(answer(command, (String) transfer(command.txId().value()).get("msg_id"), CsmMessageType.PACS_008,
                status));
        return published(command.txId().value(), type);
    }

    /** A transfer the CSM took, without an answer. */
    private SendCreditTransfer sent() {
        String txId = txId();
        SendCreditTransfer command = sendCreditTransfer(txId, START.minusSeconds(1));
        publishCommand(txId, command, UUID.randomUUID());
        await().atMost(HANDLED).until(() -> "DELIVERED".equals(transfer(txId).get("transmit_status")));
        return command;
    }

    private record Investigated(SendCreditTransfer command, String requestMsgId) {}

    /** A sent transfer whose status is requested (attempt 1): the {@code pacs.028} delivered to the CSM. */
    private Investigated investigated() {
        SendCreditTransfer command = sent();
        String txId = command.txId().value();
        publishCommand(txId, requestPaymentStatus(command, 1), UUID.randomUUID());
        String select = """
                SELECT msg_id FROM status_requests WHERE tx_id = ? AND attempt = 1 AND transmit_status = 'DELIVERED'""";
        await().atMost(HANDLED).until(() -> !jdbc.sql(select).param(txId).query(String.class).list().isEmpty());
        return new Investigated(command, jdbc.sql(select).param(txId).query(String.class).single());
    }

    /** The CSM's {@code pacs.002} answering the message {@code originalMsgId} of {@code originalType}. */
    private String answer(SendCreditTransfer command, String originalMsgId, CsmMessageType originalType,
            Status status) {
        return envelopes.join(new Pacs002Builder().build(new StatusReport(
                new MsgId("SIMM-PACT-" + UUID.randomUUID().toString().substring(0, 8)), CLOCK.instant(),
                new MsgId(originalMsgId), originalType, command.txId(), command.endToEndId(), command.uetr(), OWN_BIC,
                status, CSM_BIC, OWN_BIC)));
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
