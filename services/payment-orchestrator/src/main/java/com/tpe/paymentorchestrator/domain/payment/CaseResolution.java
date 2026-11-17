package com.tpe.paymentorchestrator.domain.payment;

/**
 * How an operator resolves an exception case (specs/11-payment-orchestrator.md §3.2); each resolution is allowed for
 * one case type, or two for {@link #ACKNOWLEDGED}.
 */
public enum CaseResolution {
    /** A new status request with the next attempt; the investigation and its back-off restart now. */
    RETRY_INVESTIGATION(ExceptionCaseType.UNRESOLVED_INVESTIGATION),
    /** As if {@code CreditTransferSettled} arrived: the hold is settled. */
    CONFIRMED_SETTLED(ExceptionCaseType.UNRESOLVED_INVESTIGATION),
    /** As if {@code CreditTransferRejected} arrived with the operator's reason: the hold is released. */
    CONFIRMED_REJECTED(ExceptionCaseType.UNRESOLVED_INVESTIGATION),
    /** Seen; nothing changes (the money correction is manual, UC-12). */
    ACKNOWLEDGED(ExceptionCaseType.CONTRADICTING_CSM_OUTCOME, ExceptionCaseType.DLT_MESSAGE),
    /** The stuck ledger action is due at once, pending since now. */
    RETRY_NOW(ExceptionCaseType.LEDGER_ACTION_STUCK);

    private final ExceptionCaseType[] types;

    CaseResolution(ExceptionCaseType... types) {
        this.types = types;
    }

    /** This resolution may close a case of {@code type}. */
    public boolean resolves(ExceptionCaseType type) {
        for (ExceptionCaseType resolvable : types) {
            if (resolvable == type) {
                return true;
            }
        }
        return false;
    }

    /** The resolution needs the operator's {@code reasonCode}. */
    public boolean needsReasonCode() {
        return this == CONFIRMED_REJECTED;
    }
}
