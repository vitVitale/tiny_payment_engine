package com.tpe.csmgateway.application.investigation;

import com.tpe.contracts.Direction;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.TxId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * {@code status_requests} (specs/15-csm-gateway.md §5, §6.5, §6.6): the {@code pacs.028} sent for each status request
 * attempt of a transaction. Every time decision takes {@code now} from the caller.
 */
public interface StatusRequests {

    /**
     * One status request.
     *
     * @param debtorAgentBic with {@code txId} the transaction (ADR-010): our own BIC for an outgoing transfer
     * @param attempt the orchestrator's attempt; never reused for a payment
     * @param msgId the {@code pacs.028}'s {@code MsgId}
     */
    record StatusRequest(Bic debtorAgentBic, TxId txId, Direction direction, int attempt, MsgId msgId,
            Instant createdAt) {

        public StatusRequest {
            Objects.requireNonNull(debtorAgentBic, "debtorAgentBic");
            Objects.requireNonNull(txId, "txId");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(msgId, "msgId");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /**
     * Inserts the request {@code PENDING}, due at once.
     *
     * @return whether it was inserted: {@code false} if this attempt of the transaction is there already
     */
    boolean insert(StatusRequest request);

    /** The request whose {@code pacs.028} had {@code msgId}. */
    Optional<StatusRequest> findByMsgId(MsgId msgId);

    /** The {@code msgId}s of the {@code PENDING} requests due at {@code now}, the longest due first. */
    List<MsgId> due(Instant now, int limit);

    /**
     * Claims the next transmission of a {@code PENDING} request that is due and has had fewer than
     * {@code maxAttempts}: counts the attempt and moves {@code next_transmit_at} a lease ahead (§6.5). Commits on its
     * own.
     *
     * @return the attempt this claim counted, empty if the caller holds no claim
     */
    OptionalInt claim(MsgId msgId, Instant now, Duration lease, int maxAttempts);

    /** {@code DELIVERED}, unless the row is no longer {@code PENDING}. Commits on its own. */
    void delivered(MsgId msgId);

    /** The next attempt at {@code at}, unless another claim took the row after {@code attempt}. */
    void retryAt(MsgId msgId, int attempt, Instant at);

    /** {@code GAVE_UP} if it is {@code PENDING}, due at {@code now} and has had {@code maxAttempts}. */
    void giveUpIfSpent(MsgId msgId, Instant now, int maxAttempts);
}
