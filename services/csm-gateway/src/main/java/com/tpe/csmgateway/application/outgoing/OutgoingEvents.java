package com.tpe.csmgateway.application.outgoing;

import com.tpe.contracts.CreditTransferNotSent;
import com.tpe.contracts.CreditTransferRejected;
import com.tpe.contracts.CreditTransferSettled;
import com.tpe.contracts.Direction;
import com.tpe.contracts.Message;
import com.tpe.contracts.SettlementSource;
import com.tpe.csmgateway.domain.outgoing.FinalStatus;
import com.tpe.csmgateway.domain.outgoing.OutgoingTransfer;
import com.tpe.csmgateway.domain.outgoing.StatusSource;
import com.tpe.messaging.outbox.OutboxWriter;
import com.tpe.observability.context.Spans;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.StatusReason;
import com.tpe.paymentdomain.TxId;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Instant;

/**
 * The events of an outgoing transfer on {@code csm.inbound.events} (specs/02-messaging-contracts.md §4), written to
 * the outbox in the caller's transaction, inside the payment's stored trace (ADR-014 §2).
 */
public class OutgoingEvents {

    private final OutboxWriter outbox;
    private final Spans spans;
    private final Bic ownBic;

    /** @param ownBic the debtor agent of every outgoing transfer */
    public OutgoingEvents(OutboxWriter outbox, Spans spans, Bic ownBic) {
        this.outbox = outbox;
        this.spans = spans;
        this.ownBic = ownBic;
    }

    public void settled(OutgoingTransfer transfer, Instant at, StatusSource source, String spanName) {
        write(transfer, spanName, new CreditTransferSettled(transfer.txId(), ownBic, Direction.OUTGOING,
                transfer.paymentId(), at, settlementSource(source)));
    }

    public void rejected(OutgoingTransfer transfer, StatusReason reason, Instant at, StatusSource source,
            String spanName) {
        write(transfer, spanName, new CreditTransferRejected(transfer.txId(), ownBic, Direction.OUTGOING,
                transfer.paymentId(), reason, at, settlementSource(source)));
    }

    public void notSent(OutgoingTransfer transfer, StatusReason reason, Instant decidedAt, String spanName) {
        write(transfer, spanName, new CreditTransferNotSent(transfer.txId(), ownBic, Direction.OUTGOING,
                transfer.paymentId(), reason, transfer.sendBy(), decidedAt));
    }

    /**
     * {@code CreditTransferNotSent} for a transfer the gateway never received: nothing was sent. Its {@code sendBy} is
     * not known here, so it is {@code decidedAt}. Written in the current trace.
     */
    public void neverArrived(TxId txId, PaymentId paymentId, StatusReason reason, Instant decidedAt) {
        outbox.write(CreditTransferSending.EVENTS, txId.value(), new CreditTransferNotSent(txId, ownBic,
                Direction.OUTGOING, paymentId, reason, decidedAt, decidedAt));
    }

    /** The final event as it was first published, from the recorded final status. */
    public void republishFinal(OutgoingTransfer transfer, String spanName) {
        // a final status recorded before its source was kept came from a status report
        StatusSource source = transfer.finalSource() == null ? StatusSource.STATUS_REPORT : transfer.finalSource();
        if (transfer.finalStatus() == FinalStatus.SETTLED) {
            settled(transfer, transfer.finalAt(), source, spanName);
        } else {
            rejected(transfer, ReasonCode.parse(transfer.finalReason()), transfer.finalAt(), source, spanName);
        }
    }

    private void write(OutgoingTransfer transfer, String spanName, Message event) {
        Span span = (transfer.traceContext() == null ? spans.newTrace(spanName)
                : spans.childOf(spanName, transfer.traceContext())).start();
        try (Tracer.SpanInScope ignored = spans.inScope(span)) {
            outbox.write(CreditTransferSending.EVENTS, transfer.txId().value(), event);
        } finally {
            span.end();
        }
    }

    private static SettlementSource settlementSource(StatusSource source) {
        return switch (source) {
            case STATUS_REPORT -> SettlementSource.STATUS_REPORT;
            case INVESTIGATION -> SettlementSource.INVESTIGATION;
        };
    }
}
