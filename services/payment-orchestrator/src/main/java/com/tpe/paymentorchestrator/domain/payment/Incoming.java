package com.tpe.paymentorchestrator.domain.payment;

import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.StatusReason;
import java.time.Instant;
import java.util.Objects;

/**
 * What an incoming payment has besides the common fields (specs/11-payment-orchestrator.md §5): the {@code MsgId} of
 * its {@code pacs.008}, the moment the gateway answers by itself, and the verdict sent, if any.
 *
 * @param answerDeadline after it the gateway answers {@code RJCT AB05} (UC-06 6b); remote calls before the verdict
 *     leave {@code safety-margin} before it (§6.3)
 * @param verdict {@code null} until decided
 * @param verdictReason the reason of a {@code REJECT} verdict, else {@code null}
 */
public record Incoming(MsgId msgId, Instant answerDeadline, Verdict verdict, StatusReason verdictReason) {

    public Incoming {
        Objects.requireNonNull(msgId, "msgId");
        Objects.requireNonNull(answerDeadline, "answerDeadline");
        if ((verdict == Verdict.REJECT) != (verdictReason != null)) {
            throw new IllegalArgumentException("a REJECT verdict, and only one, has a reason");
        }
    }

    Incoming withVerdict(Verdict decided, StatusReason reason) {
        return new Incoming(msgId, answerDeadline, Objects.requireNonNull(decided, "decided"), reason);
    }
}
