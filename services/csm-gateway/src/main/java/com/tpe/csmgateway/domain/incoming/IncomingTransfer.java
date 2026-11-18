package com.tpe.csmgateway.domain.incoming;

import com.tpe.csmgateway.domain.outgoing.FinalStatus;
import com.tpe.csmgateway.domain.outgoing.StatusSource;
import com.tpe.csmgateway.domain.transmit.TransmitStatus;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.TxId;
import java.time.Instant;
import java.util.Objects;

/**
 * One row of {@code incoming_transfers} (specs/15-csm-gateway.md §5): a {@code pacs.008} the CSM delivered, named by
 * its debtor agent and {@code TxId} (ADR-010), and the one answer the gateway sends for it.
 *
 * @param msgId the {@code pacs.008}'s {@code MsgId}
 * @param answerStatus {@code null} until an answer is decided
 * @param answerReason the reason code of a {@code RJCT} answer
 * @param answerMsgId the {@code MsgId} of the answer {@code pacs.002}; every transmission sends that one
 * @param transmitStatus {@code null} until an answer is decided
 * @param finalStatus what the CSM reported last of the transaction, settled or rejected; {@code null} until then
 * @param finalReason the reason code of a rejection
 * @param finalSource whether a status report or the answer to our {@code pacs.028} gave the final status
 * @param traceContext the root of the incoming payment's trace (ADR-014), {@code null} if there was none
 */
public record IncomingTransfer(
        Bic debtorAgentBic,
        TxId txId,
        MsgId msgId,
        Instant receivedAt,
        Instant answerDeadline,
        AnswerStatus answerStatus,
        String answerReason,
        DecidedBy answerDecidedBy,
        Instant answeredAt,
        MsgId answerMsgId,
        TransmitStatus transmitStatus,
        int transmitAttempts,
        Instant nextTransmitAt,
        FinalStatus finalStatus,
        String finalReason,
        Instant finalAt,
        StatusSource finalSource,
        String traceContext) {

    /** Our answer's {@code TxSts}. */
    public enum AnswerStatus { ACCP, RJCT }

    /** What decided the answer (§6.2, §6.3). */
    public enum DecidedBy { VERDICT, DEADLINE, VALIDATION }

    public IncomingTransfer {
        Objects.requireNonNull(debtorAgentBic, "debtorAgentBic");
        Objects.requireNonNull(txId, "txId");
        Objects.requireNonNull(msgId, "msgId");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(answerDeadline, "answerDeadline");
    }

    /** A new transfer, awaiting its answer. */
    public static IncomingTransfer received(Bic debtorAgentBic, TxId txId, MsgId msgId, Instant receivedAt,
            Instant answerDeadline, String traceContext) {
        return new IncomingTransfer(debtorAgentBic, txId, msgId, receivedAt, answerDeadline, null, null, null, null,
                null, null, 0, null, null, null, null, null, traceContext);
    }

    /** A transfer answered {@code RJCT} with {@code reason} at once, its answer due for transmission. */
    public static IncomingTransfer rejectedOnReceipt(Bic debtorAgentBic, TxId txId, MsgId msgId, Instant receivedAt,
            Instant answerDeadline, String reason, DecidedBy decidedBy, MsgId answerMsgId, String traceContext) {
        return new IncomingTransfer(debtorAgentBic, txId, msgId, receivedAt, answerDeadline, AnswerStatus.RJCT,
                reason, decidedBy, receivedAt, answerMsgId, TransmitStatus.PENDING, 0, receivedAt, null, null, null,
                null, traceContext);
    }

    public boolean answered() {
        return answerStatus != null;
    }
}
