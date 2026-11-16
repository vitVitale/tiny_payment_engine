package com.tpe.csmgateway.domain.outgoing;

import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.TxId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code outgoing_transfers} (specs/15-csm-gateway.md §5): what the gateway decided for a
 * {@code SendCreditTransfer} and what became of it.
 *
 * @param notSentReason {@code AB03} or {@code FF01} when {@code NOT_SENT}
 * @param transmitStatus {@code null} when {@code NOT_SENT}
 * @param finalReason the reason code of a rejection
 * @param traceContext the payment's W3C traceparent (ADR-014), {@code null} if there was none
 */
public record OutgoingTransfer(
        TxId txId,
        PaymentId paymentId,
        UUID uetr,
        String msgId,
        Instant acceptedAt,
        Instant sendBy,
        Instant deadlineAt,
        SendStatus sendStatus,
        String notSentReason,
        Instant journalledAt,
        TransmitStatus transmitStatus,
        int transmitAttempts,
        Instant nextTransmitAt,
        Instant deliveredAt,
        FinalStatus finalStatus,
        String finalReason,
        Instant finalAt,
        String traceContext) {

    public enum SendStatus { SENT, NOT_SENT }

    public enum TransmitStatus { PENDING, DELIVERED, GAVE_UP }

    public OutgoingTransfer {
        Objects.requireNonNull(txId, "txId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(uetr, "uetr");
        Objects.requireNonNull(msgId, "msgId");
        Objects.requireNonNull(sendStatus, "sendStatus");
    }
}
