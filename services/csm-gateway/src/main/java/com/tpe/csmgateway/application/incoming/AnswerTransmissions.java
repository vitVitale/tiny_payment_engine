package com.tpe.csmgateway.application.incoming;

import com.tpe.csmgateway.application.journal.MessageJournal;
import com.tpe.csmgateway.application.transmit.TransmissionQueue;
import com.tpe.csmgateway.domain.incoming.IncomingTransfer;
import com.tpe.iso20022model.CsmMessageType;
import com.tpe.paymentdomain.MsgId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The journalled answers to incoming transfers as a {@link TransmissionQueue} (specs/15-csm-gateway.md §6.5): an
 * answer may be claimed until {@code answer_deadline + answer-transmit-window}, whatever decided it; then it is given
 * up, as the CSM stops waiting for it shortly after the deadline. Every attempt sends the same {@code pacs.002}
 * ({@code answer_msg_id}), on the incoming payment's trace.
 */
public class AnswerTransmissions implements TransmissionQueue {

    private final IncomingTransfers transfers;
    private final MessageJournal journal;
    private final Duration window;

    /** @param window {@code tpe.csm.answer-transmit-window} */
    public AnswerTransmissions(IncomingTransfers transfers, MessageJournal journal, Duration window) {
        this.transfers = transfers;
        this.journal = journal;
        this.window = Objects.requireNonNull(window, "window");
    }

    @Override
    public String messageType() {
        return CsmMessageType.PACS_002.version();
    }

    @Override
    public List<MsgId> due(Instant now, int limit) {
        return transfers.dueAnswers(now, limit);
    }

    /** The envelope is read before the claim, so a claim counts an attempt only when there is something to send. */
    @Override
    public Optional<Claim> claim(MsgId msgId, Instant now, Duration lease) {
        IncomingTransfer transfer = transfers.findByAnswerMsgId(msgId).orElse(null);
        if (transfer == null) {
            return Optional.empty();
        }
        String envelope = journal.outboundXml(msgId.value())
                .orElseThrow(() -> new IllegalStateException("no journalled answer " + msgId.value()));
        var attempt = transfers.claimAnswer(msgId, now, lease, window);
        return attempt.isEmpty() ? Optional.empty()
                : Optional.of(new Claim(msgId, attempt.getAsInt(), envelope, transfer.traceContext()));
    }

    @Override
    public void delivered(MsgId msgId, Instant at) {
        transfers.answerDelivered(msgId);
    }

    @Override
    public void retryAt(MsgId msgId, int attempt, Instant at) {
        transfers.retryAnswerAt(msgId, attempt, at);
    }

    @Override
    public void giveUpIfExpired(MsgId msgId, Instant now) {
        transfers.giveUpAnswerIfExpired(msgId, now, window);
    }
}
