package com.tpe.contracts;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.StatusReason;
import com.tpe.paymentdomain.TxId;
import java.time.Instant;

/**
 * Gateway → orchestrator on {@code csm.inbound.events}: the CSM rejected a credit transfer, outgoing or incoming
 * (specs/02-messaging-contracts.md §4). {@code paymentId} is present exactly on {@code OUTGOING}.
 */
@Contract(type = "CreditTransferRejected", version = 1)
public record CreditTransferRejected(
        TxId txId,
        Bic debtorAgentBic,
        Direction direction,
        @OptionalField PaymentId paymentId,
        StatusReason reasonCode,
        Instant rejectedAt,
        SettlementSource source) implements Message {}
