package com.tpe.contracts;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.StatusReason;
import com.tpe.paymentdomain.TxId;
import java.time.Instant;

/**
 * Orchestrator → gateway on {@code csm.outbound.commands}: reject this incoming credit transfer with
 * {@code reasonCode} (specs/02-messaging-contracts.md §3.3). A code this version does not know is kept as received.
 */
@Contract(type = "RejectCreditTransfer", version = 1)
public record RejectCreditTransfer(
        PaymentId paymentId,
        TxId txId,
        Bic debtorAgentBic,
        Instant decidedAt,
        StatusReason reasonCode) implements Message {}
