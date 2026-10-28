package com.tpe.contracts;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.TxId;
import java.time.Instant;

/**
 * Orchestrator → gateway on {@code csm.outbound.commands}: ask the CSM for the status of a payment
 * (specs/02-messaging-contracts.md §3.2). {@code attempt} starts at 1 and is never reused for a payment.
 */
@Contract(type = "RequestPaymentStatus", version = 1)
public record RequestPaymentStatus(
        PaymentId paymentId,
        TxId txId,
        Bic debtorAgentBic,
        Direction direction,
        Integer attempt,
        Instant requestedAt) implements Message {}
