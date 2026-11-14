package com.tpe.paymentorchestrator.domain.payment;

/** What an exception case is about (specs/11-payment-orchestrator.md §3.2, §5); an operator resolves it. */
public enum ExceptionCaseType {
    /** The investigation budget ran out without a CSM outcome (UC-05). */
    UNRESOLVED_INVESTIGATION,
    /** Two contradicting decisions were made, e.g. a release answered {@code HOLD_SETTLED} (ADR-009). */
    CONTRADICTING_CSM_OUTCOME,
    /** A ledger action retried forever has been pending for {@code tpe.runner.stuck-after}. */
    LEDGER_ACTION_STUCK,
    /** A message ended in a dead-letter topic. */
    DLT_MESSAGE
}
