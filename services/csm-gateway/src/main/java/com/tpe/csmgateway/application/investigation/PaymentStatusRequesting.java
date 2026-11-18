package com.tpe.csmgateway.application.investigation;

import com.tpe.contracts.Direction;
import com.tpe.contracts.Message;
import com.tpe.contracts.RequestPaymentStatus;
import com.tpe.csmgateway.application.GatewayMetrics;
import com.tpe.csmgateway.application.incoming.IncomingEvents;
import com.tpe.csmgateway.application.incoming.IncomingIdentity;
import com.tpe.csmgateway.application.incoming.IncomingTransfers;
import com.tpe.csmgateway.application.investigation.StatusRequests.StatusRequest;
import com.tpe.csmgateway.application.journal.MessageJournal;
import com.tpe.csmgateway.application.journal.MessageJournal.Entry;
import com.tpe.csmgateway.application.outgoing.CreditTransferSending;
import com.tpe.csmgateway.application.outgoing.OutgoingEvents;
import com.tpe.csmgateway.application.outgoing.OutgoingTransfers;
import com.tpe.csmgateway.application.transmit.Transmitter;
import com.tpe.csmgateway.domain.incoming.IncomingTransfer;
import com.tpe.csmgateway.domain.outgoing.OutgoingTransfer;
import com.tpe.csmgateway.domain.outgoing.OutgoingTransfer.SendStatus;
import com.tpe.iso20022model.CsmMessageType;
import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.EnvelopeCodec.Accepted;
import com.tpe.iso20022model.Pacs028Builder;
import com.tpe.iso20022model.pacs008v08.Document;
import com.tpe.messaging.inbox.InboxGuard;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import com.tpe.paymentdomain.UlidGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code RequestPaymentStatus} (specs/15-csm-gateway.md §6.6, UC-05 2–3, UC-06 8b), in the listener
 * transaction, once per {@code (debtorAgentBic, txId, attempt)}:
 *
 * <ul>
 *   <li>no transfer (the {@code SendCreditTransfer} never arrived): {@code CreditTransferNotSent(AB03)}, nothing was
 *       sent, and a {@code SendCreditTransfer} that still arrives is a duplicate;
 *   <li>{@code NOT_SENT}: {@code CreditTransferNotSent} again (UC-05 2a);
 *   <li>a final status: its event again, as first published;
 *   <li>{@code SENT}, no final status: a {@code status_requests} row and its {@code pacs.028}, journalled and
 *       transmitted after the commit (UC-05 3).
 * </ul>
 *
 * For an incoming transfer, see {@link #requestIncoming}. The orchestrator deduplicates what is published again.
 */
public class PaymentStatusRequesting {

    private static final Logger log = LoggerFactory.getLogger(PaymentStatusRequesting.class);
    static final String NO_INCOMING_TRANSFER = "status request for no incoming transfer";
    static final String NO_JOURNALLED_PACS_008 = "status request, no journalled pacs.008";

    private static final String SPAN = "csm.status-request";

    private final OutgoingTransfers transfers;
    private final IncomingTransfers incoming;
    private final StatusRequests requests;
    private final MessageJournal journal;
    private final InboxGuard inbox;
    private final OutgoingEvents events;
    private final IncomingEvents incomingEvents;
    private final Transmitter transmitter;
    private final GatewayMetrics metrics;
    private final Clock clock;
    private final UlidGenerator ulids;
    private final Bic ownBic;
    private final Bic csmBic;
    private final EnvelopeCodec envelopes = new EnvelopeCodec();
    private final Pacs028Builder pacs028 = new Pacs028Builder();

    /**
     * @param transmitter transmits the {@code pacs.028}s
     * @param csmBic the CSM's BIC, {@code tpe.csm.bic}: the instructed agent of a {@code pacs.028}
     */
    public PaymentStatusRequesting(OutgoingTransfers transfers, IncomingTransfers incoming, StatusRequests requests,
            MessageJournal journal, InboxGuard inbox, OutgoingEvents events, IncomingEvents incomingEvents,
            Transmitter transmitter, GatewayMetrics metrics, Clock clock, UlidGenerator ulids, Bic ownBic,
            Bic csmBic) {
        this.transfers = transfers;
        this.incoming = incoming;
        this.requests = requests;
        this.journal = journal;
        this.inbox = inbox;
        this.events = events;
        this.incomingEvents = incomingEvents;
        this.transmitter = transmitter;
        this.metrics = metrics;
        this.clock = clock;
        this.ulids = ulids;
        this.ownBic = ownBic;
        this.csmBic = csmBic;
    }

    @Transactional
    public void request(UUID eventId, RequestPaymentStatus command) {
        String dedupKey = "RequestPaymentStatus:" + command.debtorAgentBic().value() + ":" + command.txId().value()
                + ":" + command.attempt();
        if (!inbox.firstTime(dedupKey, CreditTransferSending.COMMANDS, eventId)) {
            return;
        }
        Instant now = clock.instant();
        if (command.direction() == Direction.INCOMING) {
            requestIncoming(command, now);
            return;
        }
        OutgoingTransfer transfer = transfers.lock(command.txId()).orElse(null);
        if (transfer == null) {
            log.warn("status request {} for {}, which never arrived: NOT_SENT AB03", command.attempt(),
                    command.txId().value());
            // a SendCreditTransfer that still arrives (say, replayed from the DLT) is a duplicate: never sent
            inbox.firstTime(CreditTransferSending.dedupKey(command.debtorAgentBic(), command.txId()),
                    CreditTransferSending.COMMANDS, eventId);
            events.neverArrived(command.txId(), command.paymentId(), ReasonCode.AB03, now);
        } else if (transfer.sendStatus() == SendStatus.NOT_SENT) {
            events.notSent(transfer, ReasonCode.parse(transfer.notSentReason()), now, SPAN);
        } else if (transfer.finalStatus() != null) {
            events.republishFinal(transfer, SPAN);
        } else {
            investigate(transfer, command.attempt(), now);
        }
    }

    /**
     * An incoming transfer, by the command's debtor agent and {@code TxId} (ADR-010): a final status → its event
     * again; none yet → a {@code pacs.028} naming it by {@code OrgnlTxRef/DbtrAgt}; no transfer → journalled, logged
     * as the error it is and counted in {@code tpe.csm.uncorrelated}, which alerts (the orchestrator asks only about
     * transfers the gateway handed it).
     */
    private void requestIncoming(RequestPaymentStatus command, Instant now) {
        IncomingTransfer transfer = incoming.lock(command.debtorAgentBic(), command.txId()).orElse(null);
        if (transfer == null) {
            log.error("status request {} for incoming {} from {}, which the gateway never received",
                    command.attempt(), command.txId().value(), command.debtorAgentBic().value());
            unanswerable(command, NO_INCOMING_TRANSFER, now);
            return;
        }
        if (transfer.finalStatus() != null) {
            incomingEvents.republishFinal(transfer, SPAN);
            return;
        }
        // the pacs.008 as it arrived: a transfer with a payment was valid, so its EndToEndId and UETR are there
        IncomingIdentity identity = journal.inboundXml(transfer.msgId().value(), transfer.txId(),
                        transfer.debtorAgentBic()).flatMap(IncomingIdentity::read)
                .filter(read -> read.endToEndId() != null && read.uetr() != null)
                .orElse(null);
        if (identity == null) {
            // a permanent condition: thrown, the command would be retried forever and hold up the topic
            log.error("status request {} for incoming {} from {}: no journalled pacs.008 to name it by",
                    command.attempt(), command.txId().value(), command.debtorAgentBic().value());
            unanswerable(command, NO_JOURNALLED_PACS_008, now);
            return;
        }
        MsgId msgId = MsgId.generate(ulids);
        if (!requests.insert(new StatusRequest(transfer.debtorAgentBic(), transfer.txId(), Direction.INCOMING,
                command.attempt(), msgId, now))) {
            return;
        }
        send(msgId, transfer.txId(), transfer.debtorAgentBic(), new Pacs028Builder.StatusRequest(msgId, now,
                transfer.msgId(), transfer.txId(), identity.endToEndId(), identity.uetr(), transfer.debtorAgentBic(),
                ownBic, csmBic), now);
    }

    /** A status request the gateway cannot ask the CSM: journalled and counted for the alert, nothing sent. */
    private void unanswerable(RequestPaymentStatus command, String note, Instant now) {
        journal.append(new Entry(MessageJournal.Direction.IN, Message.contractOf(RequestPaymentStatus.class).type(),
                null, command.txId(), command.debtorAgentBic(), "", true, List.of(), note, now));
        metrics.uncorrelated();
    }

    /** The {@code pacs.028}, journalled {@code OUT} and transmitted after the commit. */
    private void send(MsgId msgId, TxId txId, Bic debtorAgentBic, Pacs028Builder.StatusRequest request, Instant now) {
        String xml = envelopes.join(pacs028.build(request));
        journal.append(new Entry(MessageJournal.Direction.OUT, CsmMessageType.PACS_028.version(), msgId.value(),
                txId, debtorAgentBic, xml, true, List.of(), null, now));
        metrics.message(MessageJournal.Direction.OUT, CsmMessageType.PACS_028.version());
        transmitter.transmitAfterCommit(msgId);
    }

    /** The {@code pacs.008}'s {@code TxId}, {@code EndToEndId} and {@code UETR} as it was journalled and sent. */
    private void investigate(OutgoingTransfer transfer, int attempt, Instant now) {
        MsgId msgId = MsgId.generate(ulids);
        if (!requests.insert(new StatusRequest(ownBic, transfer.txId(), Direction.OUTGOING, attempt, msgId, now))) {
            // the same attempt under another event id: its pacs.028 is on its way already
            return;
        }
        send(msgId, transfer.txId(), ownBic, new Pacs028Builder.StatusRequest(msgId, now,
                new MsgId(transfer.msgId()), transfer.txId(), endToEndId(transfer),
                new Uetr(transfer.uetr().toString()), ownBic, ownBic, csmBic), now);
    }

    private EndToEndId endToEndId(OutgoingTransfer transfer) {
        String xml = journal.outboundXml(transfer.msgId())
                .orElseThrow(() -> new IllegalStateException("no journalled pacs.008 " + transfer.msgId()));
        var document = (Document) ((Accepted) envelopes.split(xml)).envelope().document();
        return new EndToEndId(document.getFIToFICstmrCdtTrf().getCdtTrfTxInf().getFirst().getPmtId()
                .getEndToEndId());
    }
}
