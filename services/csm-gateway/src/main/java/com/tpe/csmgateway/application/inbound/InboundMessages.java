package com.tpe.csmgateway.application.inbound;

import com.tpe.csmgateway.application.GatewayMetrics;
import com.tpe.csmgateway.application.correlation.StatusReportCorrelation;
import com.tpe.csmgateway.application.incoming.CreditTransferReceiving;
import com.tpe.csmgateway.application.incoming.IncomingIdentity;
import com.tpe.csmgateway.application.journal.MessageJournal;
import com.tpe.csmgateway.application.journal.MessageJournal.Direction;
import com.tpe.csmgateway.application.journal.MessageJournal.Entry;
import com.tpe.iso20022model.CsmEnvelope;
import com.tpe.iso20022model.CsmMessageType;
import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.EnvelopeCodec.Accepted;
import com.tpe.iso20022model.EnvelopeCodec.Part;
import com.tpe.iso20022model.EnvelopeCodec.Rejected;
import com.tpe.iso20022model.XsdValidator.Violation;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * What arrives on the CSM inbound endpoint (specs/15-csm-gateway.md §3.1, §6.2, §6.4), in one transaction: the
 * envelope is validated and the message routed by type, a {@code pacs.008} to {@link CreditTransferReceiving}, a
 * {@code pacs.002} to {@link StatusReportCorrelation}. Every message is journalled {@code IN}. One that fails
 * validation but names an incoming transfer (its {@code TxId} and debtor agent can be read) is answered
 * {@code RJCT FF01}. A {@code pacs.008} that names none, and anything whose envelope cannot be parsed, is refused
 * ({@code 400}) without a journal entry; any other invalid message is journalled invalid.
 */
public class InboundMessages {

    /** The outcome of {@link #receive(String)}. */
    public sealed interface Receipt permits Journalled, Unparsable {}

    /** Journalled (valid or not); the transport answer is {@code 202}. */
    public record Journalled() implements Receipt {}

    /**
     * Not even the envelope can be parsed, or it is a {@code pacs.008} whose {@code TxId} and debtor agent cannot be
     * read: {@code 400} (§3.1), nothing journalled.
     *
     * @param codes the violation codes
     */
    public record Unparsable(List<String> codes) implements Receipt {}

    static final String NOT_HANDLED = "not handled";

    private static final Logger log = LoggerFactory.getLogger(InboundMessages.class);

    private final CreditTransferReceiving creditTransfers;
    private final StatusReportCorrelation statusReports;
    private final MessageJournal journal;
    private final GatewayMetrics metrics;
    private final Clock clock;
    private final EnvelopeCodec envelopes = new EnvelopeCodec();

    public InboundMessages(CreditTransferReceiving creditTransfers, StatusReportCorrelation statusReports,
            MessageJournal journal, GatewayMetrics metrics, Clock clock) {
        this.creditTransfers = creditTransfers;
        this.statusReports = statusReports;
        this.journal = journal;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public Receipt receive(String xml) {
        Instant now = clock.instant();
        EnvelopeCodec.SplitResult split = envelopes.split(xml);
        if (split instanceof Rejected rejected) {
            return invalid(xml, rejected, now);
        }
        CsmEnvelope envelope = ((Accepted) split).envelope();
        CsmMessageType type = envelope.messageType();
        metrics.message(Direction.IN, type.version());
        switch (type) {
            case PACS_008 -> {
                Optional<List<String>> unnamed = creditTransfers.receive(xml, envelope, now);
                if (unnamed.isPresent()) {
                    return new Unparsable(unnamed.get());
                }
            }
            case PACS_002 -> statusReports.correlate(xml, envelope, now);
            default -> journal.append(new Entry(Direction.IN, type.version(), null, null, null, xml, true, List.of(),
                    NOT_HANDLED, now));
        }
        return new Journalled();
    }

    private Receipt invalid(String xml, Rejected rejected, Instant now) {
        List<String> codes = rejected.violations().stream().map(Violation::code).distinct().toList();
        Optional<IncomingIdentity> incoming = IncomingIdentity.read(xml);
        if (incoming.isPresent()) {
            metrics.message(Direction.IN, CsmMessageType.PACS_008.version());
            creditTransfers.receiveInvalid(xml, incoming.get(), codes, now);
            return new Journalled();
        }
        if (rejected.part() == Part.ENVELOPE || IncomingIdentity.isCreditTransfer(xml)) {
            return new Unparsable(codes);
        }
        log.warn("journalled an invalid {} from the CSM: {}", rejected.part(), codes);
        journal.append(new Entry(Direction.IN, "UNKNOWN", null, null, null, xml, false, codes, null, now));
        return new Journalled();
    }
}
