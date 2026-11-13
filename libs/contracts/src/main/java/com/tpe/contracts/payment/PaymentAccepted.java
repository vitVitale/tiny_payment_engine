package com.tpe.contracts.payment;

import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.TxId;
import java.time.Instant;

/**
 * {@code 201} (a new payment) or {@code 200} (the payment of an earlier submit with the same {@code commandId}) of
 * {@code POST /v1/payments} (specs/11-payment-orchestrator.md §3.1, UC-01 6a).
 *
 * @param state the payment's current state: {@code RECEIVED} when new
 */
public record PaymentAccepted(PaymentId paymentId, PaymentState state, TxId txId, Instant acceptedAt) {}
