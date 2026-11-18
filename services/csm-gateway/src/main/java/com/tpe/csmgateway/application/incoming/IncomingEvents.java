package com.tpe.csmgateway.application.incoming;

import com.tpe.contracts.CreditTransferRejected;
import com.tpe.contracts.CreditTransferSettled;
import com.tpe.contracts.Direction;
import com.tpe.contracts.Message;
import com.tpe.contracts.SettlementSource;
import com.tpe.csmgateway.application.outgoing.CreditTransferSending;
import com.tpe.csmgateway.domain.incoming.IncomingTransfer;
import com.tpe.csmgateway.domain.outgoing.FinalStatus;
import com.tpe.csmgateway.domain.outgoing.StatusSource;
import com.tpe.messaging.outbox.OutboxWriter;
import com.tpe.observability.context.Spans;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.StatusReason;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Instant;

/**
 * The final events of an incoming transfer on {@code csm.inbound.events} (specs/02-messaging-contracts.md §4,
 * UC-06 8, 8a): {@code CreditTransferSettled} / {@code CreditTransferRejected} with {@code direction = INCOMING} and no
 * {@code paymentId}, which the gateway does not know. Written to the outbox in the caller's transaction, inside the
 * incoming payment's stored trace (ADR-014 §2).
 */
public class IncomingEvents {

    private final OutboxWriter outbox;
    private final Spans spans;

    public IncomingEvents(OutboxWriter outbox, Spans spans) {
        this.outbox = outbox;
        this.spans = spans;
    }

    public void settled(IncomingTransfer transfer, Instant at, StatusSource source, String spanName) {
        write(transfer, spanName, new CreditTransferSettled(transfer.txId(), transfer.debtorAgentBic(),
                Direction.INCOMING, null, at, settlementSource(source)));
    }

    public void rejected(IncomingTransfer transfer, StatusReason reason, Instant at, StatusSource source,
            String spanName) {
        write(transfer, spanName, new CreditTransferRejected(transfer.txId(), transfer.debtorAgentBic(),
                Direction.INCOMING, null, reason, at, settlementSource(source)));
    }

    /** The final event as it was first published, from the recorded final status. */
    public void republishFinal(IncomingTransfer transfer, String spanName) {
        StatusSource source = transfer.finalSource() == null ? StatusSource.STATUS_REPORT : transfer.finalSource();
        if (transfer.finalStatus() == FinalStatus.SETTLED) {
            settled(transfer, transfer.finalAt(), source, spanName);
        } else {
            rejected(transfer, ReasonCode.parse(transfer.finalReason()), transfer.finalAt(), source, spanName);
        }
    }

    private void write(IncomingTransfer transfer, String spanName, Message event) {
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
