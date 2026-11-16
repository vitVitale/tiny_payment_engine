package com.tpe.csmgateway.domain.outgoing;

/**
 * What a correlated {@code pacs.002} does to an outgoing transfer (specs/15-csm-gateway.md §6.4, GW-AC-13): the first
 * final status is recorded and published; the same status again is a duplicate (journal only); another final status
 * contradicts the recorded one, which stays, and its event is published anyway so the orchestrator opens a case
 * (UC-04 8a). {@code ACCP}, {@code UNKNOWN_TX} and unreadable statuses change nothing here (journal only).
 */
public final class StatusOutcome {

    public enum Effect { RECORD, DUPLICATE, CONTRADICTION, INFORMATIONAL }

    private StatusOutcome() {}

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
