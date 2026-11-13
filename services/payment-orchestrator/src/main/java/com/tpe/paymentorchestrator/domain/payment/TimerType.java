package com.tpe.paymentorchestrator.domain.payment;

/** The timers of a payment (specs/11-payment-orchestrator.md §6.4). */
public enum TimerType {
    /** At {@code deadline_at} of an outgoing payment, created with it. */
    OUTGOING_DEADLINE,
    /** After {@code AnswerSent(ACCP)} of an incoming payment. */
    SETTLEMENT_OVERDUE,
    /** After each status request of an investigation. */
    INVESTIGATION_RETRY
}
