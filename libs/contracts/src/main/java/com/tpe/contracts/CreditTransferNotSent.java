package com.tpe.contracts;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.StatusReason;
import com.tpe.paymentdomain.TxId;
import java.time.Instant;

/**
 * Gateway → orchestrator on {@code csm.inbound.events}: an outgoing credit transfer was not sent by {@code sendBy}
 * (specs/02-messaging-contracts.md §4). Always {@code OUTGOING}; {@code reasonCode} is always {@code AB03}.
 */
@Contract(type = "CreditTransferNotSent", version = 1)
public record CreditTransferNotSent(
        TxId txId,
        Bic debtorAgentBic,
        Direction direction,
        PaymentId paymentId,
        StatusReason reasonCode,
        Instant sendBy,
        Instant decidedAt) implements Message {}
