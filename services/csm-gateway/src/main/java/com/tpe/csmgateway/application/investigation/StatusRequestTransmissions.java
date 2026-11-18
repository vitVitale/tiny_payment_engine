package com.tpe.csmgateway.application.investigation;

import com.tpe.contracts.Direction;
import com.tpe.csmgateway.application.incoming.IncomingTransfers;
import com.tpe.csmgateway.application.investigation.StatusRequests.StatusRequest;
import com.tpe.csmgateway.application.journal.MessageJournal;
import com.tpe.csmgateway.application.outgoing.OutgoingTransfers;
import com.tpe.csmgateway.application.transmit.TransmissionQueue;
import com.tpe.csmgateway.domain.incoming.IncomingTransfer;
import com.tpe.csmgateway.domain.outgoing.OutgoingTransfer;
import com.tpe.iso20022model.CsmMessageType;
import com.tpe.paymentdomain.MsgId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The journalled {@code pacs.028}s as a {@link TransmissionQueue} (specs/15-csm-gateway.md §6.5): given up after
 * {@value #MAX_ATTEMPTS} attempts; the orchestrator's next retry sends a new one. A transmission continues the trace
 * of the payment it asks about.
 */
public class StatusRequestTransmissions implements TransmissionQueue {

    /** The attempts a {@code pacs.028} gets before it is given up. */
    public static final int MAX_ATTEMPTS = 5;

    private final StatusRequests requests;
    private final OutgoingTransfers transfers;
    private final IncomingTransfers incoming;
    private final MessageJournal journal;

    public StatusRequestTransmissions(StatusRequests requests, OutgoingTransfers transfers, IncomingTransfers incoming,
            MessageJournal journal) {
        this.requests = requests;
        this.transfers = transfers;
        this.incoming = incoming;
        this.journal = journal;
    }

    @Override
    public String messageType() {
        return CsmMessageType.PACS_028.version();
    }

    @Override
    public List<MsgId> due(Instant now, int limit) {
        return requests.due(now, limit);
    }

    /** The envelope is read before the claim, so a claim counts an attempt only when there is something to send. */
    @Override
    public Optional<Claim> claim(MsgId msgId, Instant now, Duration lease) {
        StatusRequest request = requests.findByMsgId(msgId).orElse(null);
        if (request == null) {
            return Optional.empty();
        }
        String envelope = journal.outboundXml(msgId.value())
                .orElseThrow(() -> new IllegalStateException("no journalled pacs.028 " + msgId.value()));
        String traceContext = request.direction() == Direction.OUTGOING
                ? transfers.find(request.txId()).map(OutgoingTransfer::traceContext).orElse(null)
                : incoming.find(request.txId()).stream()
                        .filter(transfer -> transfer.debtorAgentBic().equals(request.debtorAgentBic()))
                        .findFirst().map(IncomingTransfer::traceContext).orElse(null);
        var attempt = requests.claim(msgId, now, lease, MAX_ATTEMPTS);
        return attempt.isEmpty() ? Optional.empty()
                : Optional.of(new Claim(msgId, attempt.getAsInt(), envelope, traceContext));
    }

    @Override
    public void delivered(MsgId msgId, Instant at) {
        requests.delivered(msgId);
    }

    @Override
    public void retryAt(MsgId msgId, int attempt, Instant at) {
        requests.retryAt(msgId, attempt, at);
    }

    @Override
    public void giveUpIfExpired(MsgId msgId, Instant now) {
        requests.giveUpIfSpent(msgId, now, MAX_ATTEMPTS);
    }
}
