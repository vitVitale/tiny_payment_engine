package com.tpe.paymentorchestrator.application.payments;

import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentorchestrator.domain.payment.PaymentState;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;

/**
 * A runner's claim of a payment's pending action (specs/11-payment-orchestrator.md §6.3): what the payment was when
 * claimed. The result of the action is applied only while the payment is still exactly this.
 *
 * @param attempts how often the action has been claimed, this claim included
 * @param version the payment's version after the claim
 */
public record ActionClaim(PaymentId paymentId, PaymentState state, PendingAction action, int attempts,
        long version) {}
