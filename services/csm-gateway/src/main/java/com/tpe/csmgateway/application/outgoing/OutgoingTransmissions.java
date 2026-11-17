package com.tpe.csmgateway.application.outgoing;

import com.tpe.contracts.CreditTransferNotSent;
import com.tpe.contracts.Direction;
import com.tpe.csmgateway.application.GatewayMetrics;
import com.tpe.csmgateway.application.journal.MessageJournal;
import com.tpe.csmgateway.application.transmit.TransmissionQueue;
import com.tpe.csmgateway.domain.outgoing.OutgoingTransfer;
import com.tpe.csmgateway.domain.outgoing.OutgoingTransfer.TransmitStatus;
import com.tpe.iso20022model.CsmMessageType;
import com.tpe.messaging.outbox.OutboxWriter;
import com.tpe.observability.context.Spans;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.ReasonCode;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * The journalled {@code pacs.008}s as a {@link TransmissionQueue} (specs/15-csm-gateway.md §6.1, ADR-011): a
 * transfer may be claimed only before its {@code sendBy}. At {@code sendBy} it is given up; if no attempt was ever
 * claimed, no byte left, so it is {@code NOT_SENT AB03} and {@code CreditTransferNotSent} is published in the same
 * transaction. After an attempt it stays {@code SENT}: the CSM may have it, and the investigation resolves it.
 */
public class OutgoingTransmissions implements TransmissionQueue {

    private static final Logger log = LoggerFactory.getLogger(OutgoingTransmissions.class);

    private final OutgoingTransfers transfers;
    private final MessageJournal journal;
    private final OutboxWriter outbox;
    private final GatewayMetrics metrics;
    private final Spans spans;
    private final Bic ownBic;

    /** @param ownBic the debtor agent of every outgoing transfer */
    public OutgoingTransmissions(OutgoingTransfers transfers, MessageJournal journal, OutboxWriter outbox,
            GatewayMetrics metrics, Spans spans, Bic ownBic) {
        this.transfers = transfers;
        this.journal = journal;
        this.outbox = outbox;
        this.metrics = metrics;
        this.spans = spans;
        this.ownBic = ownBic;
    }

    @Override
    public String messageType() {
        return CsmMessageType.PACS_008.version();
    }

    @Override
    public List<MsgId> due(Instant now, int limit) {
        return transfers.dueTransmissions(now, limit);
    }

    /** The envelope is read before the claim, so a claim counts an attempt only when there is something to send. */
    @Override
    public Optional<Claim> claim(MsgId msgId, Instant now, Duration lease) {
        OutgoingTransfer transfer = transfers.findByMsgId(msgId).orElse(null);
        if (transfer == null) {
            return Optional.empty();
        }
        String envelope = journal.outboundXml(msgId.value())
                .orElseThrow(() -> new IllegalStateException("no journalled pacs.008 " + msgId.value()));
        var attempt = transfers.claimTransmission(msgId, now, lease);
        return attempt.isEmpty() ? Optional.empty()
                : Optional.of(new Claim(msgId, attempt.getAsInt(), envelope, transfer.traceContext()));
    }

    @Override
    public void delivered(MsgId msgId, Instant at) {
        transfers.delivered(msgId, at);
    }

    @Override
    public void retryAt(MsgId msgId, int attempt, Instant at) {
        transfers.retryAt(msgId, attempt, at);
    }

    @Override
    @Transactional
    public void giveUpIfExpired(MsgId msgId, Instant now) {
        OutgoingTransfer transfer = transfers.lockByMsgId(msgId.value()).orElse(null);
        if (transfer == null || transfer.transmitStatus() != TransmitStatus.PENDING
                || transfer.nextTransmitAt().isAfter(now) || now.isBefore(transfer.sendBy())) {
            return;
        }
        if (transfer.transmitAttempts() > 0) {
            log.warn("gave up transmitting pacs.008 {} of {} at sendBy after {} attempts; it stays SENT",
                    msgId.value(), transfer.txId().value(), transfer.transmitAttempts());
            transfers.gaveUp(msgId);
            return;
        }
        log.warn("pacs.008 {} of {} was never attempted before sendBy: NOT_SENT AB03", msgId.value(),
                transfer.txId().value());
        transfers.gaveUpNotSent(msgId, ReasonCode.AB03);
        // the event continues the payment's trace (ADR-014 §2)
        Span span = (transfer.traceContext() == null ? spans.newTrace("csm.give-up")
                : spans.childOf("csm.give-up", transfer.traceContext())).start();
        try (Tracer.SpanInScope ignored = spans.inScope(span)) {
            outbox.write(CreditTransferSending.EVENTS, transfer.txId().value(), new CreditTransferNotSent(
                    transfer.txId(), ownBic, Direction.OUTGOING, transfer.paymentId(), ReasonCode.AB03,
                    transfer.sendBy(), now));
        } finally {
            span.end();
        }
        metrics.notSent(ReasonCode.AB03.code());
    }
}
