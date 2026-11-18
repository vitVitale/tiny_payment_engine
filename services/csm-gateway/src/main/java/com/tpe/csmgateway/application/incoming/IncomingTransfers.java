package com.tpe.csmgateway.application.incoming;

import com.tpe.csmgateway.domain.incoming.IncomingTransfer;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.TxId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * {@code incoming_transfers} (specs/15-csm-gateway.md §5, §6.2, §6.5): one row per transaction, named by its debtor
 * agent and {@code TxId} (ADR-010), with the answer the gateway sends for it. A row's answer is named by its
 * {@code answer_msg_id}. Every time decision takes {@code now} from the caller.
 */
public interface IncomingTransfers {

    /** @return whether it was inserted: {@code false} if the transaction is there already */
    boolean insert(IncomingTransfer transfer);

    /** The transfer, locked for the rest of the transaction. */
    Optional<IncomingTransfer> lock(Bic debtorAgentBic, TxId txId);

    /** Every transfer with {@code txId}, of any debtor agent; no lock. */
    List<IncomingTransfer> find(TxId txId);

    /** The transfer whose answer has {@code answerMsgId}; no lock. */
    Optional<IncomingTransfer> findByAnswerMsgId(MsgId answerMsgId);

    /** Its decided answer {@code PENDING} again, due at {@code now}: the same {@code pacs.002} is sent once more. */
    void resendAnswer(Bic debtorAgentBic, TxId txId, Instant now);

    /** The {@code answer_msg_id}s of the {@code PENDING} answers due at {@code now}, the longest due first. */
    List<MsgId> dueAnswers(Instant now, int limit);

    /**
     * Claims the next transmission of a {@code PENDING} answer that is due and still before {@code answer_deadline +
     * window}: counts the attempt and moves {@code next_transmit_at} a lease ahead (§6.5). Commits on its own.
     *
     * @return the attempt this claim counted, empty if the caller holds no claim
     */
    OptionalInt claimAnswer(MsgId answerMsgId, Instant now, Duration lease, Duration window);

    /** {@code DELIVERED}, unless the answer is no longer {@code PENDING}. Commits on its own. */
    void answerDelivered(MsgId answerMsgId);

    /** The next attempt at {@code at}, unless another claim took the answer after {@code attempt}. */
    void retryAnswerAt(MsgId answerMsgId, int attempt, Instant at);

    /** {@code GAVE_UP} if it is {@code PENDING}, due at {@code now} and past {@code answer_deadline + window}. */
    void giveUpAnswerIfExpired(MsgId answerMsgId, Instant now, Duration window);
}
