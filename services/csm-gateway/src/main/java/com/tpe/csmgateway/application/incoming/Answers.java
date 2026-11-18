package com.tpe.csmgateway.application.incoming;

import com.tpe.csmgateway.application.GatewayMetrics;
import com.tpe.csmgateway.application.journal.MessageJournal;
import com.tpe.csmgateway.application.journal.MessageJournal.Direction;
import com.tpe.csmgateway.application.journal.MessageJournal.Entry;
import com.tpe.csmgateway.application.transmit.Transmitter;
import com.tpe.csmgateway.domain.incoming.IncomingTransfer;
import com.tpe.iso20022model.CsmMessageType;
import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.Pacs002Builder;
import com.tpe.iso20022model.Pacs002Builder.Status;
import com.tpe.iso20022model.Pacs002Builder.StatusReport;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.MsgId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The answer {@code pacs.002} to an incoming transfer, whatever decided it (specs/15-csm-gateway.md §6.2, §6.3): from
 * our BIC to the CSM's, naming the transaction by {@code OrgnlTxRef/DbtrAgt} and {@code OrgnlTxId}. It is journalled
 * {@code OUT} in the caller's transaction and transmitted once that commits (§6.5); a re-send transmits the journalled
 * one again, under the same {@code answer_msg_id}.
 */
public class Answers {

    private final MessageJournal journal;
    private final Transmitter transmitter;
    private final GatewayMetrics metrics;
    private final Bic ownBic;
    private final Bic csmBic;
    private final EnvelopeCodec envelopes = new EnvelopeCodec();
    private final Pacs002Builder statusReports = new Pacs002Builder();

    /** @param transmitter transmits the answers */
    public Answers(MessageJournal journal, Transmitter transmitter, GatewayMetrics metrics, Bic ownBic, Bic csmBic) {
        this.journal = journal;
        this.transmitter = transmitter;
        this.metrics = metrics;
        this.ownBic = ownBic;
        this.csmBic = csmBic;
    }

    /**
     * Sends the answer just decided for {@code transfer}, a row whose {@code answer_*} columns are set.
     *
     * @param identity what the {@code pacs.008} carried, for its {@code OrgnlEndToEndId} and {@code OrgnlUETR}
     */
    public void send(IncomingTransfer transfer, IncomingIdentity identity, Status status, Instant now) {
        MsgId answerMsgId = transfer.answerMsgId();
        String answer = envelopes.join(statusReports.build(new StatusReport(answerMsgId, now, transfer.msgId(),
                CsmMessageType.PACS_008, transfer.txId(), identity.endToEndId(), identity.uetr(),
                transfer.debtorAgentBic(), status, ownBic, csmBic)));
        journal.append(new Entry(Direction.OUT, CsmMessageType.PACS_002.version(), answerMsgId.value(),
                transfer.txId(), transfer.debtorAgentBic(), answer, true, List.of(), null, now));
        metrics.message(Direction.OUT, CsmMessageType.PACS_002.version());
        metrics.answer(transfer.answerStatus().name(), transfer.answerDecidedBy().name());
        metrics.answerMargin(Duration.between(now, transfer.answerDeadline()));
        transmitter.transmitAfterCommit(answerMsgId);
    }

    /** The journalled answer {@code answerMsgId} once more, after the commit. */
    public void resend(MsgId answerMsgId) {
        transmitter.transmitAfterCommit(answerMsgId);
    }

    /** What the journalled {@code pacs.008} of {@code transfer} carried; only its identity if it is not there. */
    public IncomingIdentity identityOf(IncomingTransfer transfer) {
        Optional<IncomingIdentity> journalled = journal.inboundXml(transfer.msgId().value(), transfer.txId(),
                transfer.debtorAgentBic()).flatMap(IncomingIdentity::read);
        return journalled.orElseGet(() -> new IncomingIdentity(transfer.txId(), transfer.debtorAgentBic(),
                transfer.msgId(), null, null));
    }
}
