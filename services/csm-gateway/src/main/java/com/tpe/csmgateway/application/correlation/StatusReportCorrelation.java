package com.tpe.csmgateway.application.correlation;

import com.tpe.contracts.CreditTransferRejected;
import com.tpe.contracts.CreditTransferSettled;
import com.tpe.contracts.Direction;
import com.tpe.contracts.SettlementSource;
import com.tpe.csmgateway.application.GatewayMetrics;
import com.tpe.csmgateway.application.journal.MessageJournal;
import com.tpe.csmgateway.application.journal.MessageJournal.Entry;
import com.tpe.csmgateway.application.outgoing.CreditTransferSending;
import com.tpe.csmgateway.application.outgoing.OutgoingTransfers;
import com.tpe.csmgateway.domain.outgoing.OutgoingTransfer;
import com.tpe.csmgateway.domain.outgoing.ReportedStatus;
import com.tpe.csmgateway.domain.outgoing.StatusOutcome;
import com.tpe.csmgateway.domain.outgoing.StatusOutcome.Effect;
import com.tpe.iso20022model.CsmEnvelope;
import com.tpe.iso20022model.CsmMessageType;
import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.EnvelopeCodec.Accepted;
import com.tpe.iso20022model.EnvelopeCodec.Part;
import com.tpe.iso20022model.EnvelopeCodec.Rejected;
import com.tpe.iso20022model.XsdValidator.Violation;
import com.tpe.iso20022model.pacs002v10.PaymentTransaction110;
import com.tpe.iso20022model.pacs002v10.StatusReason6Choice;
import com.tpe.messaging.outbox.OutboxWriter;
import com.tpe.observability.context.Spans;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.TxId;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * The CSM inbound endpoint's work for a {@code pacs.002} (specs/15-csm-gateway.md §6.4, UC-04 6–8), in one
 * transaction: validate, journal, then match {@code OrgnlTxId} with {@code OrgnlTxRef/DbtrAgt} = our BIC to an
 * outgoing transfer (fallback: the {@code OrgnlMsgId} of the {@code pacs.008}, ADR-010). The first final status is
 * recorded and published ({@code CreditTransferSettled} / {@code CreditTransferRejected}, source
 * {@code STATUS_REPORT}); the same status again is journalled only; a contradicting one keeps the first in the row
 * and is published anyway, for the orchestrator's exception case (UC-04 8a). {@code ACCP} and {@code UNKNOWN_TX} are
 * journalled only; investigations and incoming transfers follow in later work. Unmatched → journalled
 * {@code uncorrelated}, counted for the alert.
 *
 * <p>Events are written inside the payment's stored trace (ADR-014).
 */
public class StatusReportCorrelation {

    /** The outcome of {@link #receive(String)}. */
    public sealed interface Receipt permits Journalled, Unparsable {}

    /** Journalled (valid or not); the transport answer is {@code 202}. */
    public record Journalled() implements Receipt {}

    /** The envelope cannot be parsed at all: {@code 400} (§3.1), nothing journalled. */
    public record Unparsable(List<Violation> violations) implements Receipt {}

    static final String UNCORRELATED = "uncorrelated";
    static final String DUPLICATE = "duplicate status";
    static final String CONTRADICTING = "contradicting status, first kept";
    static final String INFORMATIONAL = "informational, no effect";
    static final String NOT_HANDLED = "not handled yet";

    private static final Logger log = LoggerFactory.getLogger(StatusReportCorrelation.class);

    private final OutgoingTransfers transfers;
    private final MessageJournal journal;
    private final OutboxWriter outbox;
    private final GatewayMetrics metrics;
    private final Spans spans;
    private final Clock clock;
    private final Bic ownBic;
    private final EnvelopeCodec envelopes = new EnvelopeCodec();

    public StatusReportCorrelation(OutgoingTransfers transfers, MessageJournal journal, OutboxWriter outbox,
            GatewayMetrics metrics, Spans spans, Clock clock, Bic ownBic) {
        this.transfers = transfers;
        this.journal = journal;
        this.outbox = outbox;
        this.metrics = metrics;
        this.spans = spans;
        this.clock = clock;
        this.ownBic = ownBic;
    }

    @Transactional
    public Receipt receive(String xml) {
        Instant now = clock.instant();
        EnvelopeCodec.SplitResult split = envelopes.split(xml);
        if (split instanceof Rejected rejected) {
            if (rejected.part() == Part.ENVELOPE) {
                return new Unparsable(rejected.violations());
            }
            List<String> codes = rejected.violations().stream().map(Violation::code).distinct().toList();
            log.warn("journalled an invalid {} from the CSM: {}", rejected.part(), codes);
            journal.append(new Entry(MessageJournal.Direction.IN, "UNKNOWN", null, null, null, xml, false, codes,
                    null, now));
            return new Journalled();
        }
        CsmEnvelope envelope = ((Accepted) split).envelope();
        metrics.message(MessageJournal.Direction.IN, envelope.messageType().version());
        if (envelope.messageType() != CsmMessageType.PACS_002) {
            journal.append(new Entry(MessageJournal.Direction.IN, envelope.messageType().version(), null, null, null,
                    xml, true, List.of(), NOT_HANDLED, now));
            return new Journalled();
        }
        var report = ((com.tpe.iso20022model.pacs002v10.Document) envelope.document()).getFIToFIPmtStsRpt();
        String msgId = report.getGrpHdr().getMsgId();
        Optional<PaymentTransaction110> first = report.getTxInfAndSts().stream().findFirst();
        TxId txId = first.map(t -> txId(t.getOrgnlTxId())).orElse(null);
        Bic debtorAgent = first.map(StatusReportCorrelation::debtorAgent).orElse(null);
        String originalMsgId = report.getOrgnlGrpInfAndSts().stream().findFirst()
                .map(g -> g.getOrgnlMsgId()).orElse(null);

        Optional<OutgoingTransfer> transfer = match(txId, debtorAgent, originalMsgId);
        String note;
        if (transfer.isEmpty()) {
            note = UNCORRELATED;
            metrics.uncorrelated();
            log.warn("uncorrelated pacs.002 {} for {}", msgId, txId == null ? null : txId.value());
        } else {
            note = apply(transfer.get(), first.map(StatusReportCorrelation::status).orElse(ReportedStatus.unreadable()),
                    now);
        }
        journal.append(new Entry(MessageJournal.Direction.IN, envelope.messageType().version(), msgId,
                transfer.map(OutgoingTransfer::txId).orElse(txId), debtorAgent, xml, true, List.of(), note, now));
        return new Journalled();
    }

    /** A {@code DbtrAgt} other than ours names an incoming transfer, which this gateway does not handle yet. */
    private Optional<OutgoingTransfer> match(TxId txId, Bic debtorAgent, String originalMsgId) {
        if (debtorAgent != null && !ownBic.equals(debtorAgent)) {
            return Optional.empty();
        }
        if (txId != null) {
            Optional<OutgoingTransfer> byTxId = transfers.lock(txId);
            if (byTxId.isPresent()) {
                return byTxId;
            }
        }
        return originalMsgId == null ? Optional.empty() : transfers.lockByMsgId(originalMsgId);
    }

    /** @return the journal note */
    private String apply(OutgoingTransfer transfer, ReportedStatus status, Instant now) {
        Effect effect = StatusOutcome.of(transfer.finalStatus(), status);
        switch (effect) {
            case INFORMATIONAL -> {
                return INFORMATIONAL;
            }
            case DUPLICATE -> {
                return DUPLICATE;
            }
            case RECORD -> transfers.recordFinal(transfer.txId(), status.finalStatus(),
                    status.reason() == null ? null : status.reason().code(), now);
            case CONTRADICTION -> log.warn("contradicting pacs.002 for {}: {} after {}", transfer.txId().value(),
                    status.kind(), transfer.finalStatus());
        }
        publish(transfer, status, now);
        return effect == Effect.CONTRADICTION ? CONTRADICTING : null;
    }

    private void publish(OutgoingTransfer transfer, ReportedStatus status, Instant now) {
        Span span = (transfer.traceContext() == null ? spans.newTrace("csm.status-report")
                : spans.childOf("csm.status-report", transfer.traceContext())).start();
        try (Tracer.SpanInScope ignored = spans.inScope(span)) {
            String key = transfer.txId().value();
            switch (status.kind()) {
                case SETTLED -> outbox.write(CreditTransferSending.EVENTS, key, new CreditTransferSettled(
                        transfer.txId(), ownBic, Direction.OUTGOING, transfer.paymentId(), now,
                        SettlementSource.STATUS_REPORT));
                case REJECTED -> outbox.write(CreditTransferSending.EVENTS, key, new CreditTransferRejected(
                        transfer.txId(), ownBic, Direction.OUTGOING, transfer.paymentId(), status.reason(), now,
                        SettlementSource.STATUS_REPORT));
                default -> throw new IllegalArgumentException("no event for " + status.kind());
            }
        } finally {
            span.end();
        }
    }

    private static ReportedStatus status(PaymentTransaction110 transaction) {
        StatusReason6Choice reason = transaction.getStsRsnInf().stream().findFirst()
                .map(information -> information.getRsn()).orElse(null);
        return ReportedStatus.of(transaction.getTxSts(), reason == null ? null : reason.getCd(),
                reason == null ? null : reason.getPrtry());
    }

    private static Bic debtorAgent(PaymentTransaction110 transaction) {
        try {
            return new Bic(transaction.getOrgnlTxRef().getDbtrAgt().getFinInstnId().getBICFI());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static TxId txId(String value) {
        try {
            return value == null ? null : new TxId(value);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
