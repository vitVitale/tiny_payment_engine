package com.tpe.csmgateway.application.outgoing;

import com.tpe.contracts.CreditTransferNotSent;
import com.tpe.contracts.Direction;
import com.tpe.contracts.SendCreditTransfer;
import com.tpe.csmgateway.application.GatewayMetrics;
import com.tpe.csmgateway.application.journal.MessageJournal;
import com.tpe.csmgateway.application.journal.MessageJournal.Entry;
import com.tpe.csmgateway.application.transmit.Transmitter;
import com.tpe.csmgateway.domain.outgoing.OutgoingTransfer;
import com.tpe.csmgateway.domain.outgoing.OutgoingTransfer.SendStatus;
import com.tpe.csmgateway.domain.transmit.TransmitStatus;
import com.tpe.iso20022model.CsmEnvelope;
import com.tpe.iso20022model.CsmMessageType;
import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.EnvelopeCodec.InvalidEnvelopeException;
import com.tpe.iso20022model.IsoCodec;
import com.tpe.iso20022model.Pacs008Builder;
import com.tpe.iso20022model.Pacs008Builder.AccountHolder;
import com.tpe.iso20022model.Pacs008Builder.CreditTransfer;
import com.tpe.iso20022model.XsdValidator.Violation;
import com.tpe.messaging.inbox.InboxGuard;
import com.tpe.messaging.outbox.OutboxWriter;
import com.tpe.observability.context.Spans;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.UlidGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code SendCreditTransfer} (specs/15-csm-gateway.md §6.1, ADR-004, UC-04 5): in the listener transaction, the
 * command is handled once (inbox, dedup key {@code SendCreditTransfer:<debtorAgentBic>:<txId>}, 02 §3) and a
 * {@code txId} already known is a no-op. At or after {@code sendBy} nothing is sent: {@code NOT_SENT AB03} and
 * {@code CreditTransferNotSent(AB03)}. Otherwise the {@code pacs.008} is built with a new {@code MsgId},
 * {@code AccptncDtTm = acceptedAt} and {@code IntrBkSttlmDt} = the UTC date of now, and validated: invalid →
 * {@code NOT_SENT FF01} and {@code CreditTransferNotSent(FF01)}, journalled invalid, logged as the bug it is; valid →
 * {@code SENT}, {@code PENDING}, journalled
 * {@code OUT}. Once that commits the {@code pacs.008} is journalled as sent and the decision is final; the transmitter
 * sends it after the commit.
 */
public class CreditTransferSending {

    /** The command topic, also the inbox's {@code topic}. */
    public static final String COMMANDS = "csm.outbound.commands";
    /** The events topic, key {@code txId} (02 §2). */
    public static final String EVENTS = "csm.inbound.events";

    private static final Logger log = LoggerFactory.getLogger(CreditTransferSending.class);

    private final OutgoingTransfers transfers;
    private final MessageJournal journal;
    private final InboxGuard inbox;
    private final OutboxWriter outbox;
    private final Transmitter transmitter;
    private final GatewayMetrics metrics;
    private final Spans spans;
    private final Clock clock;
    private final UlidGenerator ulids;
    private final EnvelopeCodec envelopes = new EnvelopeCodec();
    private final IsoCodec iso = new IsoCodec();
    private final Pacs008Builder pacs008 = new Pacs008Builder();

    public CreditTransferSending(OutgoingTransfers transfers, MessageJournal journal, InboxGuard inbox,
            OutboxWriter outbox, Transmitter transmitter, GatewayMetrics metrics, Spans spans, Clock clock,
            UlidGenerator ulids) {
        this.transfers = transfers;
        this.journal = journal;
        this.inbox = inbox;
        this.outbox = outbox;
        this.transmitter = transmitter;
        this.metrics = metrics;
        this.spans = spans;
        this.clock = clock;
        this.ulids = ulids;
    }

    @Transactional
    public void send(UUID eventId, SendCreditTransfer command) {
        if (!inbox.firstTime(dedupKey(command.debtorAgentBic(), command.txId()), COMMANDS, eventId)
                || transfers.exists(command.txId())) {
            return;
        }
        Instant now = clock.instant();
        MsgId msgId = MsgId.generate(ulids);
        String traceContext = spans.currentTraceparent().orElse(null);
        if (!now.isBefore(command.sendBy())) {
            notSent(command, msgId, ReasonCode.AB03, now, traceContext);
            return;
        }
        CsmEnvelope envelope = pacs008.build(creditTransfer(command, msgId, now));
        String xml;
        try {
            xml = envelopes.join(envelope);
        } catch (InvalidEnvelopeException e) {
            // the builder only makes valid documents: this is a bug, and it must page (§6.1)
            log.error("built an invalid pacs.008 for {}: {}", command.txId().value(),
                    e.violations().stream().map(Violation::code).distinct().toList());
            notSent(command, msgId, ReasonCode.FF01, now, traceContext);
            journal.append(entry(command, msgId, iso.marshal(envelope.document()), false,
                    e.violations().stream().map(Violation::code).distinct().toList(), now));
            return;
        }
        transfers.insert(transfer(command, msgId, SendStatus.SENT, null, TransmitStatus.PENDING, now, traceContext));
        journal.append(entry(command, msgId, xml, true, List.of(), now));
        metrics.message(MessageJournal.Direction.OUT, CsmMessageType.PACS_008.version());
        transmitter.transmitAfterCommit(msgId);
    }

    /**
     * The inbox key of a {@code SendCreditTransfer} (02 §3). Taken also when a status request finds no transfer, so
     * one that arrives after the {@code CreditTransferNotSent} is a duplicate and never sent (§6.6).
     */
    public static String dedupKey(Bic debtorAgentBic, TxId txId) {
        return "SendCreditTransfer:" + debtorAgentBic.value() + ":" + txId.value();
    }

    /** {@code NOT_SENT} with {@code reason} and its {@code CreditTransferNotSent}, in the listener transaction. */
    private void notSent(SendCreditTransfer command, MsgId msgId, ReasonCode reason, Instant now,
            String traceContext) {
        transfers.insert(transfer(command, msgId, SendStatus.NOT_SENT, reason.code(), null, now, traceContext));
        outbox.write(EVENTS, command.txId().value(), new CreditTransferNotSent(command.txId(),
                command.debtorAgentBic(), Direction.OUTGOING, command.paymentId(), reason, command.sendBy(), now));
        metrics.notSent(reason.code());
    }

    private static CreditTransfer creditTransfer(SendCreditTransfer command, MsgId msgId, Instant now) {
        return new CreditTransfer(msgId, now, command.txId(), command.endToEndId(), command.uetr(), command.amount(),
                LocalDate.ofInstant(now, ZoneOffset.UTC), command.acceptedAt(),
                new AccountHolder(command.debtor().name(), command.debtor().iban()), command.debtorAgentBic(),
                command.creditorAgentBic(), new AccountHolder(command.creditor().name(), command.creditor().iban()),
                command.remittanceInformation());
    }

    private static OutgoingTransfer transfer(SendCreditTransfer command, MsgId msgId, SendStatus sendStatus,
            String notSentReason, TransmitStatus transmitStatus, Instant now, String traceContext) {
        return new OutgoingTransfer(command.txId(), command.paymentId(), UUID.fromString(command.uetr().value()),
                msgId.value(), command.acceptedAt(), command.sendBy(), command.deadlineAt(), sendStatus,
                notSentReason, now, transmitStatus, 0, transmitStatus == null ? null : now, null, null, null, null,
                null, traceContext);
    }

    private static Entry entry(SendCreditTransfer command, MsgId msgId, String xml, boolean valid,
            List<String> errors, Instant now) {
        return new Entry(MessageJournal.Direction.OUT, CsmMessageType.PACS_008.version(), msgId.value(),
                command.txId(), command.debtorAgentBic(), xml, valid, errors, null, now);
    }
}
