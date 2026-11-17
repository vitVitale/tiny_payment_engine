package com.tpe.paymentorchestrator.domain.payment;

/** {@code payments.csm_outcome} (specs/11-payment-orchestrator.md §5): what the CSM decided about the payment. */
public enum CsmOutcome { SETTLED, REJECTED, NOT_SENT }
