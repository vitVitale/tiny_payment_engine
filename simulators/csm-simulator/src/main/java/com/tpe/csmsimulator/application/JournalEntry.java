package com.tpe.csmsimulator.application;

import com.tpe.paymentdomain.TxId;
import java.time.Instant;
import java.util.Objects;

/**
 * A message the simulator received or sent for a transaction ({@code GET /sim/v1/received},
 * specs/17-csm-simulator.md §3.5).
 *
 * @param txId the transaction it is about; {@code null} for a message naming none
 * @param messageType the message version, e.g. {@code pacs.008.001.08}
 * @param duplicate a received message whose {@code MsgId} was already processed
 * @param envelope the envelope as received or sent
 */
public record JournalEntry(
        TxId txId, Kind kind, String messageType, String msgId, Instant at, boolean duplicate, String envelope) {

    public enum Kind { RECEIVED, SENT }

    public JournalEntry {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(messageType, "messageType");
        Objects.requireNonNull(msgId, "msgId");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(envelope, "envelope");
    }
}
