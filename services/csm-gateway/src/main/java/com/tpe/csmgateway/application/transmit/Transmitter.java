package com.tpe.csmgateway.application.transmit;

import com.tpe.csmgateway.application.journal.MessageJournal;
import com.tpe.csmgateway.application.outgoing.OutgoingTransfers;
import com.tpe.csmgateway.domain.outgoing.OutgoingTransfer;
import com.tpe.observability.context.Spans;
import com.tpe.paymentdomain.TxId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executor;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transmits a journalled {@code pacs.008} to the CSM (specs/15-csm-gateway.md §6.5): claim (a conditional update that
 * counts the attempt and takes a lease), then the HTTP call with no lock held, then {@code DELIVERED} on {@code 2xx}.
 * A failed attempt leaves the row {@code PENDING}. This is the {@code afterCommit} fast path, one attempt; retries,
 * lease takeover and giving up at {@code sendBy} belong to the transmit worker.
 */
public class Transmitter {

    private static final Logger log = LoggerFactory.getLogger(Transmitter.class);

    private final OutgoingTransfers transfers;
    private final MessageJournal journal;
    private final CsmClient csm;
    private final Clock clock;
    private final Duration lease;
    private final Executor executor;
    private final Spans spans;

    /** @param executor runs the fast path off the committing thread, whose connection is still bound */
    public Transmitter(OutgoingTransfers transfers, MessageJournal journal, CsmClient csm, Clock clock,
            Duration lease, Executor executor, Spans spans) {
        this.transfers = transfers;
        this.journal = journal;
        this.csm = csm;
        this.clock = clock;
        this.lease = lease;
        this.executor = executor;
        this.spans = spans;
    }

    /** The {@code afterCommit} fast path: one attempt, soon, on the transmit executor. */
    public void transmitSoon(TxId txId) {
        executor.execute(() -> {
            try {
                transmit(txId);
            } catch (RuntimeException e) {
                log.error("transmission of {} failed", txId.value(), e);
            }
        });
    }

    /** Outside any transaction: each step commits on its own. */
    public void transmit(TxId txId) {
        if (!transfers.claimTransmission(txId, clock.instant(), lease)) {
            return;
        }
        OutgoingTransfer transfer = transfers.find(txId).orElseThrow();
        String msgId = transfer.msgId();
        String envelope = journal.outboundXml(msgId)
                .orElseThrow(() -> new IllegalStateException("no journalled pacs.008 " + msgId));
        // a transmission continues the payment's trace (ADR-014 §2)
        Span span = (transfer.traceContext() == null ? spans.newTrace("csm.transmit")
                : spans.childOf("csm.transmit", transfer.traceContext())).start();
        try (Tracer.SpanInScope ignored = spans.inScope(span)) {
            csm.send(envelope);
        } catch (CsmClient.CsmUnavailableException e) {
            span.error(e);
            // the message is the status or the exception type only, never the CSM's body (00 §11)
            log.warn("CSM did not take pacs.008 {} of {}: {}", msgId, txId.value(), e.getMessage());
            return;
        } finally {
            span.end();
        }
        Instant deliveredAt = clock.instant();
        transfers.delivered(txId, deliveredAt);
    }
}
