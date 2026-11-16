package com.tpe.csmgateway.application.outgoing;

import com.tpe.csmgateway.domain.outgoing.FinalStatus;
import com.tpe.csmgateway.domain.outgoing.OutgoingTransfer;
import com.tpe.paymentdomain.TxId;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** {@code outgoing_transfers} (specs/15-csm-gateway.md §5). Every time decision takes {@code now} from the caller. */
public interface OutgoingTransfers {

    boolean exists(TxId txId);

    void insert(OutgoingTransfer transfer);

    /** The transfer, locked for the rest of the transaction. */
    Optional<OutgoingTransfer> lock(TxId txId);

    /** The transfer whose {@code pacs.008} had {@code msgId}, locked for the rest of the transaction. */
    Optional<OutgoingTransfer> lockByMsgId(String msgId);

    /** Records the first final status; the row keeps it against any later, contradicting one. */
    void recordFinal(TxId txId, FinalStatus status, String reason, Instant at);

    /**
     * Claims the next transmission of a {@code PENDING} transfer that is due and still before its {@code sendBy}:
     * counts the attempt and moves {@code next_transmit_at} a lease ahead, so no other claim takes it meanwhile
     * (specs/15-csm-gateway.md §6.5). Commits on its own.
     *
     * @return whether this caller holds the claim
     */
    boolean claimTransmission(TxId txId, Instant now, Duration lease);

    /** Commits on its own. */
    void delivered(TxId txId, Instant at);

    /** The transfer, as it is; no lock. */
    Optional<OutgoingTransfer> find(TxId txId);
}
