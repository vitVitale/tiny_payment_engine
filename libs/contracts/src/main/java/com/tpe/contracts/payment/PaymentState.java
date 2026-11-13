package com.tpe.contracts.payment;

/**
 * The state of a payment in the orchestrator (specs/11-payment-orchestrator.md §6.1); payment-api maps it to the
 * customer-facing status (UC-07).
 */
public enum PaymentState {
    RECEIVED,
    RISK_CHECKED,
    FUNDS_RESERVED,
    SENT_TO_CSM,
    ACCEPT_REQUESTED,
    ACCEPTED,
    INVESTIGATING,
    SETTLED,
    REJECTED
}
