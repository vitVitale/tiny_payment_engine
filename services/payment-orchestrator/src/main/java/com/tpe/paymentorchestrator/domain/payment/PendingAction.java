package com.tpe.paymentorchestrator.domain.payment;

/**
 * A payment's next remote step (specs/11-payment-orchestrator.md §6.2): set by the transaction that enters a state,
 * performed by the saga runner outside any transaction, its result applied by a second transaction.
 */
public enum PendingAction {
    /** risk-service, in the payment's direction. */
    ASSESS_RISK,
    CREATE_HOLD,
    /** Local: {@code SendCreditTransfer} into the outbox while {@code now < send_by}. */
    SEND,
    /** On-us: risk-service, direction {@code INCOMING}, for the payee. */
    ASSESS_PAYEE_RISK,
    SETTLE_ON_US,
    SETTLE_HOLD,
    RELEASE_HOLD,
    CREATE_PENDING_CREDIT,
    CONFIRM_CREDIT,
    CANCEL_CREDIT
}
