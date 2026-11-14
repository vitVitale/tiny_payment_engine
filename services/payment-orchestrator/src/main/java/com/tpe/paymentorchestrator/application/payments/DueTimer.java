package com.tpe.paymentorchestrator.application.payments;

import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentorchestrator.domain.payment.TimerType;
import java.time.Instant;

/** A {@code SCHEDULED} timer whose {@code due_at} has come (specs/11-payment-orchestrator.md §6.4). */
public record DueTimer(long timerId, PaymentId paymentId, TimerType type, Instant dueAt) {}
