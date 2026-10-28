package com.tpe.contracts;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.TxId;
import java.time.Instant;

/**
 * Orchestrator → gateway on {@code csm.outbound.commands}: accept this incoming credit transfer
 * (specs/02-messaging-contracts.md §3.3).
 */
@Contract(type = "AcceptCreditTransfer", version = 1)
public record AcceptCreditTransfer(
        PaymentId paymentId,
        TxId txId,
        Bic debtorAgentBic,
        Instant decidedAt) implements Message {}
