package com.tpe.paymentorchestrator.domain.payment;

/** The VoP result payment-api's reference check returned; risk weighs it (specs/11-payment-orchestrator.md §3.1). */
public enum PayeeVerification {
    MATCH,
    CLOSE_MATCH,
    NO_MATCH,
    NOT_POSSIBLE
}
