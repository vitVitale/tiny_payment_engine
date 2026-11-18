package com.tpe.paymentorchestrator.domain.payment;

/**
 * What the orchestrator decided for an incoming payment (specs/11-payment-orchestrator.md §5 {@code verdict}): the
 * command it sent the gateway. The payment's state follows the answer the gateway actually sent (ADR-003).
 */
public enum Verdict {
    /** {@code AcceptCreditTransfer}: the pending credit exists. */
    ACCEPT,
    /** {@code RejectCreditTransfer} with the verdict's reason. */
    REJECT
}
