package com.tpe.csmgateway.application.transmit;

import com.tpe.paymentdomain.MsgId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * One kind of row the transmitter sends (specs/15-csm-gateway.md §6.5): {@code pacs.008}s now, answers and status
 * requests later. A row is named by the {@code MsgId} of the message it sends. A kind decides two things: until when
 * a row may be claimed (its give-up window) and what giving up means for it.
 */
public interface TransmissionQueue {

    /** The {@code type} tag of {@code tpe.csm.transmit.attempts}, e.g. {@code pacs.008.001.08}. */
    String messageType();

    /** The {@code PENDING} rows due at {@code now}, those past their give-up window too; the longest due first. */
    List<MsgId> due(Instant now, int limit);

    /**
     * Claims the next attempt of a {@code PENDING} row that is due and inside its give-up window: counts the attempt
     * and takes a lease. Commits on its own, before any HTTP call.
     *
     * @return what to send, empty if the caller holds no claim
     */
    Optional<Claim> claim(MsgId msgId, Instant now, Duration lease);

    /** {@code DELIVERED}. Commits on its own. */
    void delivered(MsgId msgId, Instant at);

    /** The next attempt at {@code at}, unless another claim took the row after {@code attempt}. Commits on its own. */
    void retryAt(MsgId msgId, int attempt, Instant at);

    /**
     * Gives up a {@code PENDING} row that is due and past its give-up window, with whatever that means for the kind,
     * in one transaction. Does nothing to any other row.
     */
    void giveUpIfExpired(MsgId msgId, Instant now);

    /**
     * A claimed attempt.
     *
     * @param attempt the attempts made, this one included
     * @param traceContext the W3C traceparent the transmission continues, {@code null} if there is none
     */
    record Claim(MsgId msgId, int attempt, String envelope, String traceContext) {}
}
