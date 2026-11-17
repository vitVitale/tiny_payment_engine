package com.tpe.paymentorchestrator.application.payments;

import com.tpe.paymentorchestrator.domain.payment.Payment;
import java.time.Instant;

/**
 * A payment whose row the current transaction locked (specs/11-payment-orchestrator.md §6.6): no runner result or
 * other handler changes it until the commit.
 *
 * @param version the row's version; a runner result claimed at an older one is discarded
 * @param csmOutcomeRecorded {@code csm_outcome} is set: the CSM decided, and timers change nothing
 * @param investigationStartedAt the start of the current investigation; {@code null} if none started
 */
public record LockedPayment(Payment payment, long version, boolean csmOutcomeRecorded,
        Instant investigationStartedAt) {}
