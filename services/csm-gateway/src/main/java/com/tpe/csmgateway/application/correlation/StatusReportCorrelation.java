package com.tpe.csmgateway.application.correlation;

import com.tpe.csmgateway.application.GatewayMetrics;
import com.tpe.csmgateway.application.investigation.StatusRequests;
import com.tpe.csmgateway.application.investigation.StatusRequests.StatusRequest;
import com.tpe.csmgateway.application.journal.MessageJournal;
import com.tpe.csmgateway.application.journal.MessageJournal.Entry;
import com.tpe.csmgateway.application.outgoing.OutgoingEvents;
import com.tpe.csmgateway.application.outgoing.OutgoingTransfers;
import com.tpe.csmgateway.domain.outgoing.OutgoingTransfer;
import com.tpe.csmgateway.domain.outgoing.ReportedStatus;
import com.tpe.csmgateway.domain.outgoing.StatusOutcome;
import com.tpe.csmgateway.domain.outgoing.StatusOutcome.Effect;
import com.tpe.csmgateway.domain.outgoing.StatusSource;
import com.tpe.iso20022model.CsmEnvelope;
import com.tpe.iso20022model.pacs002v10.PaymentTransaction110;
import com.tpe.iso20022model.pacs002v10.StatusReason6Choice;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.DomainValidationException;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.TxId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The CSM inbound endpoint's work for a schema-valid {@code pacs.002} (specs/15-csm-gateway.md §6.4, UC-04 6–8), in one
 * transaction: journal, then match {@code OrgnlTxId} with {@code OrgnlTxRef/DbtrAgt} = our BIC to an
 * outgoing transfer (fallback: the {@code OrgnlMsgId}, of the {@code pacs.008} or of a {@code pacs.028} we sent,
 * ADR-010). The source is {@code INVESTIGATION} when the report answers a {@code pacs.028} we sent (its
 * {@code OrgnlMsgId} is a {@code status_requests.msg_id}), else {@code STATUS_REPORT}. The first final status is
 * recorded and published ({@code CreditTransferSettled} / {@code CreditTransferRejected}); the same status again is
 * journalled only; a contradicting one keeps the first in the row and is published anyway, for the orchestrator's
 * exception case (UC-04 8a). {@code UNKNOWN_TX} answering our {@code pacs.028} for a {@code SENT} transfer with no
 * final status past its {@code deadline_at} is a rejection {@code AB05} (UC-05 4c); otherwise, like {@code ACCP}, it
 * is journalled only.
 * Incoming transfers follow in later work. Unmatched → journalled {@code uncorrelated}, counted for the alert.
 *
 * <p>Events are written inside the payment's stored trace (ADR-014).
 */
public class StatusReportCorrelation {

    static final String UNCORRELATED = "uncorrelated";
    static final String DUPLICATE = "duplicate status";
    static final String CONTRADICTING = "contradicting status, first kept";
    static final String INFORMATIONAL = "informational, no effect";

    private static final Logger log = LoggerFactory.getLogger(StatusReportCorrelation.class);
    private static final String SPAN = "csm.status-report";

    private final OutgoingTransfers transfers;
    private final StatusRequests statusRequests;
    private final MessageJournal journal;
    private final OutgoingEvents events;
    private final GatewayMetrics metrics;
    private final Bic ownBic;

    public StatusReportCorrelation(OutgoingTransfers transfers, StatusRequests statusRequests, MessageJournal journal,
            OutgoingEvents events, GatewayMetrics metrics, Bic ownBic) {
        this.transfers = transfers;
        this.statusRequests = statusRequests;
        this.journal = journal;
        this.events = events;
        this.metrics = metrics;
        this.ownBic = ownBic;
    }

    /** A schema-valid {@code pacs.002}, journalled with what it did; the caller's transaction. */
    public void correlate(String xml, CsmEnvelope envelope, Instant now) {
        var report = ((com.tpe.iso20022model.pacs002v10.Document) envelope.document()).getFIToFIPmtStsRpt();
        String msgId = report.getGrpHdr().getMsgId();
        Optional<PaymentTransaction110> first = report.getTxInfAndSts().stream().findFirst();
        TxId txId = first.map(t -> txId(t.getOrgnlTxId())).orElse(null);
        Bic debtorAgent = first.map(StatusReportCorrelation::debtorAgent).orElse(null);
        String originalMsgId = report.getOrgnlGrpInfAndSts().stream().findFirst()
                .map(g -> g.getOrgnlMsgId()).orElse(null);

        Optional<StatusRequest> answered = answered(originalMsgId);
        Optional<OutgoingTransfer> transfer = match(txId, debtorAgent, originalMsgId, answered);
        String note;
        if (transfer.isEmpty()) {
            note = UNCORRELATED;
            metrics.uncorrelated();
            log.warn("uncorrelated pacs.002 {} for {}", msgId, txId == null ? null : txId.value());
        } else {
            note = apply(transfer.get(), first.map(StatusReportCorrelation::status).orElse(ReportedStatus.unreadable()),
                    answered.isPresent() ? StatusSource.INVESTIGATION : StatusSource.STATUS_REPORT, now);
        }
        journal.append(new Entry(MessageJournal.Direction.IN, envelope.messageType().version(), msgId,
                transfer.map(OutgoingTransfer::txId).orElse(txId), debtorAgent, xml, true, List.of(), note, now));
    }

    /** The status request whose {@code pacs.028} this report answers, if it answers one. */
    private Optional<StatusRequest> answered(String originalMsgId) {
        MsgId msgId;
        try {
            msgId = originalMsgId == null ? null : new MsgId(originalMsgId);
        } catch (DomainValidationException e) {
            // not a MsgId this gateway could have sent
            return Optional.empty();
        }
        return msgId == null ? Optional.empty() : statusRequests.findByMsgId(msgId);
    }

    /** A {@code DbtrAgt} other than ours names an incoming transfer, which this gateway does not handle yet. */
    private Optional<OutgoingTransfer> match(TxId txId, Bic debtorAgent, String originalMsgId,
            Optional<StatusRequest> answered) {
        if (debtorAgent != null && !ownBic.equals(debtorAgent)) {
            return Optional.empty();
        }
        if (txId != null) {
            Optional<OutgoingTransfer> byTxId = transfers.lock(txId);
            if (byTxId.isPresent()) {
                return byTxId;
            }
        }
        if (answered.isPresent()) {
            return transfers.lock(answered.get().txId());
        }
        return originalMsgId == null ? Optional.empty() : transfers.lockByMsgId(originalMsgId);
    }

    /** @return the journal note */
    private String apply(OutgoingTransfer transfer, ReportedStatus reported, StatusSource source, Instant now) {
        // UNKNOWN_TX decides only as the answer to our pacs.028 (§6.4)
        ReportedStatus status = source != StatusSource.INVESTIGATION ? reported
                : StatusOutcome.effective(reported, transfer.finalStatus(), transfer.sendStatus(),
                        transfer.deadlineAt(), now);
        Effect effect = StatusOutcome.of(transfer.finalStatus(), status);
        switch (effect) {
            case INFORMATIONAL -> {
                return INFORMATIONAL;
            }
            case DUPLICATE -> {
                return DUPLICATE;
            }
            case RECORD -> transfers.recordFinal(transfer.txId(), status.finalStatus(),
                    status.reason() == null ? null : status.reason().code(), now, source);
            case CONTRADICTION -> log.warn("contradicting pacs.002 for {}: {} after {}", transfer.txId().value(),
                    status.kind(), transfer.finalStatus());
        }
        switch (status.kind()) {
            case SETTLED -> events.settled(transfer, now, source, SPAN);
            case REJECTED -> events.rejected(transfer, status.reason(), now, source, SPAN);
            default -> throw new IllegalArgumentException("no event for " + status.kind());
        }
        return effect == Effect.CONTRADICTION ? CONTRADICTING : null;
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
