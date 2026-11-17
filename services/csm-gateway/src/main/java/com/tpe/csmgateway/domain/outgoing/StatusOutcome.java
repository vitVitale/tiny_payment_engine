package com.tpe.csmgateway.domain.outgoing;

import com.tpe.csmgateway.domain.outgoing.OutgoingTransfer.SendStatus;
import com.tpe.paymentdomain.ReasonCode;
import java.time.Instant;

/**
 * What a correlated {@code pacs.002} does to an outgoing transfer (specs/15-csm-gateway.md §6.4, GW-AC-13): the first
 * final status is recorded and published; the same status again is a duplicate (journal only); another final status
 * contradicts the recorded one, which stays, and its event is published anyway so the orchestrator opens a case
 * (UC-04 8a). {@code ACCP}, {@code UNKNOWN_TX} and unreadable statuses change nothing here (journal only); see
 * {@link #effective} for when {@code UNKNOWN_TX} is a rejection.
 */
public final class StatusOutcome {

    public enum Effect { RECORD, DUPLICATE, CONTRADICTION, INFORMATIONAL }

    private StatusOutcome() {}

    /**
     * What a reported status means for a transfer: {@code UNKNOWN_TX} for a {@code SENT} transfer with no final
     * status whose time-out deadline has passed is a rejection {@code AB05}, since the CSM refuses the
     * {@code pacs.008} past it (UC-05 4c, GW-AC-07); before the deadline, for a transfer never sent, or once a final
     * status is recorded (a late answer is a no-op, UC-05), it stays unknown. Any other status is as reported.
     *
     * @param recorded the transfer's final status so far, {@code null} if none
     */
    public static ReportedStatus effective(ReportedStatus reported, FinalStatus recorded, SendStatus sendStatus,
            Instant deadlineAt, Instant now) {
        if (reported.kind() == ReportedStatus.Kind.UNKNOWN_TRANSACTION && recorded == null
                && sendStatus == SendStatus.SENT && !now.isBefore(deadlineAt)) {
            return ReportedStatus.rejected(ReasonCode.AB05);
        }
        return reported;
    }

    /** @param recorded the transfer's final status so far, {@code null} if none */
    public static Effect of(FinalStatus recorded, ReportedStatus reported) {
        FinalStatus reportedFinal = reported.finalStatus();
        if (reportedFinal == null) {
            return Effect.INFORMATIONAL;
        }
        if (recorded == null) {
            return Effect.RECORD;
        }
        return recorded == reportedFinal ? Effect.DUPLICATE : Effect.CONTRADICTION;
    }
}
