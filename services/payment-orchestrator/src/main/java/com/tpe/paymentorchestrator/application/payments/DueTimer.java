package com.tpe.paymentorchestrator.application.payments;

import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentorchestrator.domain.payment.TimerType;
import java.time.Instant;

/**
 * A {@code SCHEDULED} timer whose {@code due_at} has come (specs/11-payment-orchestrator.md §6.4).
 *
 * @param attempt {@code payment_timers.attempt}: for {@code INVESTIGATION_RETRY} the status requests of its
 *     investigation so far, which pick the next back-off value; 0 for the other types
 */
public record DueTimer(long timerId, PaymentId paymentId, TimerType type, Instant dueAt, int attempt) {}
