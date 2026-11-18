package com.tpe.csmgateway.application.journal;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.TxId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The append-only {@code message_journal} (specs/15-csm-gateway.md §5): every message in and out, valid or not, with
 * a note for what the gateway did with it.
 */
public interface MessageJournal {

    enum Direction { IN, OUT }

    /**
     * One journal row.
     *
     * @param messageType the message version, e.g. {@code pacs.008.001.08}; {@code UNKNOWN} if it could not be read
     * @param msgId {@code null} if it could not be read
     * @param txId {@code null} if it could not be read
     * @param debtorAgentBic {@code null} if it could not be read
     * @param validationErrors the violation codes of an invalid message; empty if valid
     * @param note what the gateway did with it, {@code null} for the plain case
     */
    record Entry(Direction direction, String messageType, String msgId, TxId txId, Bic debtorAgentBic, String xml,
            boolean valid, List<String> validationErrors, String note, Instant at) {

        public Entry {
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(messageType, "messageType");
            Objects.requireNonNull(xml, "xml");
            Objects.requireNonNull(at, "at");
            validationErrors = List.copyOf(validationErrors);
        }
    }

    /**
     * A journalled message as the admin API shows it.
     *
     * @param transmitStatus for the {@code pacs.008} of an outgoing transfer or the answer to an incoming one, its
     *     transmit status
     */
    record Journalled(long id, Entry entry, String transmitStatus) {}

    void append(Entry entry);

    /** The {@code pacs.008} journalled {@code OUT} under {@code msgId}, as it is transmitted. */
    Optional<String> outboundXml(String msgId);

    /** Every message for {@code txId}, oldest first; {@code debtorAgentBic} narrows it to one transaction. */
    List<Journalled> byTransaction(TxId txId, Optional<Bic> debtorAgentBic);
}
