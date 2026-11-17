package com.tpe.csmgateway.application.outgoing;

import com.tpe.csmgateway.domain.outgoing.FinalStatus;
import com.tpe.csmgateway.domain.outgoing.OutgoingTransfer;
import com.tpe.csmgateway.domain.outgoing.StatusSource;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.TxId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** {@code outgoing_transfers} (specs/15-csm-gateway.md §5). Every time decision takes {@code now} from the caller. */
public interface OutgoingTransfers {

    boolean exists(TxId txId);

    void insert(OutgoingTransfer transfer);

    /** The transfer, locked for the rest of the transaction. */
    Optional<OutgoingTransfer> lock(TxId txId);

    /** The transfer whose {@code pacs.008} had {@code msgId}, locked for the rest of the transaction. */
    Optional<OutgoingTransfer> lockByMsgId(String msgId);

    /** Records the first final status; the row keeps it against any later, contradicting one. */
    void recordFinal(TxId txId, FinalStatus status, String reason, Instant at, StatusSource source);

    /** The {@code msgId}s of the {@code PENDING} transfers due at {@code now}, the longest due first. */
    List<MsgId> dueTransmissions(Instant now, int limit);

    /**
     * Claims the next transmission of a {@code PENDING} transfer that is due and still before its {@code sendBy}:
     * counts the attempt and moves {@code next_transmit_at} a lease ahead, so no other claim takes it meanwhile
     * (specs/15-csm-gateway.md §6.5, ADR-011). Commits on its own.
     *
     * @return the attempt this claim counted, empty if the caller holds no claim
     */
    OptionalInt claimTransmission(MsgId msgId, Instant now, Duration lease);

    /** {@code DELIVERED}, unless the row is no longer {@code PENDING}. Commits on its own. */
    void delivered(MsgId msgId, Instant at);

    /**
     * The next attempt at {@code at}, unless another claim took the row after {@code attempt}. Commits on its own.
     */
    void retryAt(MsgId msgId, int attempt, Instant at);

    /** {@code GAVE_UP}, still {@code SENT}: an attempt was made, so the CSM may have it (ADR-011). */
    void gaveUp(MsgId msgId);

    /** {@code GAVE_UP} and {@code NOT_SENT} with {@code reason}: no attempt was ever made (ADR-011). */
    void gaveUpNotSent(MsgId msgId, ReasonCode reason);

    /** The transfer, as it is; no lock. */
    Optional<OutgoingTransfer> find(TxId txId);

    /** The transfer whose {@code pacs.008} had {@code msgId}, as it is; no lock. */
    Optional<OutgoingTransfer> findByMsgId(MsgId msgId);
}
