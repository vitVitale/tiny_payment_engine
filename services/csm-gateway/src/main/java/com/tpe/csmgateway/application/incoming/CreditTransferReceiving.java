package com.tpe.csmgateway.application.incoming;

import com.tpe.csmgateway.application.GatewayMetrics;
import com.tpe.csmgateway.application.incoming.IncomingCreditTransfer.Refused;
import com.tpe.csmgateway.application.incoming.IncomingCreditTransfer.Taken;
import com.tpe.csmgateway.application.journal.MessageJournal;
import com.tpe.csmgateway.application.journal.MessageJournal.Direction;
import com.tpe.csmgateway.application.journal.MessageJournal.Entry;
import com.tpe.csmgateway.application.outgoing.CreditTransferSending;
import com.tpe.csmgateway.application.transmit.Transmitter;
import com.tpe.csmgateway.domain.incoming.IncomingTransfer;
import com.tpe.csmgateway.domain.incoming.IncomingTransfer.DecidedBy;
import com.tpe.iso20022model.CsmEnvelope;
import com.tpe.iso20022model.CsmMessageType;
import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.Pacs002Builder;
import com.tpe.iso20022model.Pacs002Builder.Status;
import com.tpe.iso20022model.Pacs002Builder.StatusReport;
import com.tpe.messaging.outbox.OutboxWriter;
import com.tpe.observability.context.Spans;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.UlidGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An incoming {@code pacs.008} (specs/15-csm-gateway.md §6.2, ADR-010, UC-06 1, 1a, 1b), in the CSM inbound
 * endpoint's transaction. The transaction is its debtor agent and {@code TxId}:
 *
 * <ul>
 *   <li>known already (UC-06 1b): an answer decided → the same {@code pacs.002} is sent again; none yet → dropped.
 *       Journalled with a note either way; no event.
 *   <li>new and valid (UC-06 1): {@code received_at = now}, {@code answer_deadline = now + answer-deadline-offset},
 *       the root of the incoming payment's trace (ADR-014), and {@code CreditTransferReceived} in the outbox.
 *   <li>new and invalid, but named (UC-06 1a): answered {@code RJCT FF01} by the gateway itself
 *       ({@code VALIDATION}), the answer journalled {@code OUT} and transmitted after the commit; no event, no payment.
 * </ul>
 */
public class CreditTransferReceiving {

    static final String DUPLICATE_DROPPED = "duplicate pacs.008 dropped";
    static final String DUPLICATE_ANSWERED = "duplicate pacs.008, answer re-sent";
    static final String INVALID_ANSWERED = "invalid, answered RJCT FF01";

    /** The {@code OrgnlMsgId} of an answer to a {@code pacs.008} whose {@code MsgId} could not be read. */
    static final MsgId NOT_PROVIDED = new MsgId("NOTPROVIDED");

    private static final Logger log = LoggerFactory.getLogger(CreditTransferReceiving.class);
    private static final String PACS_008 = CsmMessageType.PACS_008.version();

    private final IncomingTransfers transfers;
    private final MessageJournal journal;
    private final OutboxWriter outbox;
    private final Transmitter answers;
    private final GatewayMetrics metrics;
    private final Spans spans;
    private final UlidGenerator ulids;
    private final Bic ownBic;
    private final Bic csmBic;
    private final Duration answerDeadlineOffset;
    private final EnvelopeCodec envelopes = new EnvelopeCodec();
    private final Pacs002Builder statusReports = new Pacs002Builder();

    /**
     * @param answers transmits the answers, after the commit
     * @param csmBic the CSM's BIC, the instructed agent of an answer
     * @param answerDeadlineOffset {@code tpe.csm.answer-deadline-offset}
     */
    public CreditTransferReceiving(IncomingTransfers transfers, MessageJournal journal, OutboxWriter outbox,
            Transmitter answers, GatewayMetrics metrics, Spans spans, UlidGenerator ulids, Bic ownBic, Bic csmBic,
            Duration answerDeadlineOffset) {
        this.transfers = transfers;
        this.journal = journal;
        this.outbox = outbox;
        this.answers = answers;
        this.metrics = metrics;
        this.spans = spans;
        this.ulids = ulids;
        this.ownBic = ownBic;
        this.csmBic = csmBic;
        this.answerDeadlineOffset = Objects.requireNonNull(answerDeadlineOffset, "answerDeadlineOffset");
    }

    /**
     * A schema-valid {@code pacs.008}; the caller's transaction.
     *
     * @return the violations if its {@code TxId} or debtor agent cannot be read: nothing to answer, nothing done (§3.1)
     */
    public Optional<List<String>> receive(String xml, CsmEnvelope envelope, Instant now) {
        switch (IncomingCreditTransfer.read(envelope, ownBic)) {
            case Taken taken -> take(xml, taken.transfer(), now);
            case Refused refused when refused.identity().isPresent() ->
                    receiveInvalid(xml, refused.identity().get(), refused.violations(), now);
            case Refused refused -> {
                return Optional.of(refused.violations());
            }
        }
        return Optional.empty();
    }

    private void take(String xml, IncomingCreditTransfer transfer, Instant now) {
        IncomingIdentity identity = transfer.identity();
        if (duplicate(xml, identity, true, List.of(), now)) {
            return;
        }
        Instant answerDeadline = now.plus(answerDeadlineOffset);
        if (!transfers.insert(IncomingTransfer.received(identity.debtorAgent(), identity.txId(), identity.msgId(),
                now, answerDeadline, spans.currentTraceparent().orElse(null)))) {
            // the same pacs.008 inserted meanwhile: the insert waited for its commit
            knownAfterAll(xml, identity, true, List.of(), now);
            return;
        }
        journal.append(in(xml, identity, true, List.of(), null, now));
        outbox.write(CreditTransferSending.EVENTS, identity.txId().value(), transfer.received(now, answerDeadline));
    }

    /**
     * A {@code pacs.008} that failed validation, named by {@code identity}: answered {@code RJCT FF01}, unless the
     * transaction is known already. The caller's transaction.
     */
    public void receiveInvalid(String xml, IncomingIdentity identity, List<String> violations, Instant now) {
        if (duplicate(xml, identity, false, violations, now)) {
            return;
        }
        log.warn("invalid pacs.008 {} from {}, answered RJCT FF01: {}", identity.txId().value(),
                identity.debtorAgent().value(), violations);
        MsgId answerMsgId = MsgId.generate(ulids);
        MsgId original = identity.msgId() == null ? NOT_PROVIDED : identity.msgId();
        if (!transfers.insert(IncomingTransfer.rejectedOnReceipt(identity.debtorAgent(), identity.txId(), original,
                now, now.plus(answerDeadlineOffset), ReasonCode.FF01.code(), DecidedBy.VALIDATION, answerMsgId,
                spans.currentTraceparent().orElse(null)))) {
            knownAfterAll(xml, identity, false, violations, now);
            return;
        }
        journal.append(in(xml, identity, false, violations, INVALID_ANSWERED, now));
        String answer = envelopes.join(statusReports.build(new StatusReport(answerMsgId, now, original,
                CsmMessageType.PACS_008, identity.txId(), identity.endToEndId(), identity.uetr(),
                identity.debtorAgent(), Status.rejected(ReasonCode.FF01), ownBic, csmBic)));
        journal.append(new Entry(Direction.OUT, CsmMessageType.PACS_002.version(), answerMsgId.value(),
                identity.txId(), identity.debtorAgent(), answer, true, List.of(), null, now));
        metrics.message(Direction.OUT, CsmMessageType.PACS_002.version());
        metrics.answer(IncomingTransfer.AnswerStatus.RJCT.name(), DecidedBy.VALIDATION.name());
        answers.transmitAfterCommit(answerMsgId);
    }

    /**
     * The transaction is known already: its decided answer is sent again, or, with none yet, the message dropped.
     *
     * @return whether it was known
     */
    private boolean duplicate(String xml, IncomingIdentity identity, boolean valid, List<String> violations,
            Instant now) {
        Optional<IncomingTransfer> known = transfers.lock(identity.debtorAgent(), identity.txId());
        if (known.isEmpty()) {
            return false;
        }
        String note;
        if (known.get().answered()) {
            transfers.resendAnswer(identity.debtorAgent(), identity.txId(), now);
            answers.transmitAfterCommit(known.get().answerMsgId());
            note = DUPLICATE_ANSWERED;
        } else {
            note = DUPLICATE_DROPPED;
        }
        log.info("{} for {} from {}", note, identity.txId().value(), identity.debtorAgent().value());
        journal.append(in(xml, identity, valid, violations, note, now));
        return true;
    }

    /** The insert met the row of another transaction, committed meanwhile: it must be there now. */
    private void knownAfterAll(String xml, IncomingIdentity identity, boolean valid, List<String> violations,
            Instant now) {
        if (!duplicate(xml, identity, valid, violations, now)) {
            // rolls back: the CSM gets no 202 and sends the pacs.008 again
            throw new IllegalStateException("incoming transfer " + identity.txId().value() + " from "
                    + identity.debtorAgent().value() + " neither inserted nor found");
        }
    }

    private static Entry in(String xml, IncomingIdentity identity, boolean valid, List<String> violations,
            String note, Instant now) {
        return new Entry(Direction.IN, PACS_008, identity.msgId() == null ? null : identity.msgId().value(),
                identity.txId(), identity.debtorAgent(), xml, valid, violations, note, now);
    }
}
