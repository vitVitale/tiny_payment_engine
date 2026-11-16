package com.tpe.contracts;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.StatusReason;
import com.tpe.paymentdomain.TxId;
import java.time.Instant;

/**
 * Gateway → orchestrator on {@code csm.inbound.events}: an outgoing credit transfer was not sent
 * (specs/02-messaging-contracts.md §4). Always {@code OUTGOING}; {@code reasonCode} is {@code AB03} ({@code sendBy}
 * passed) or {@code FF01} (the {@code pacs.008} built for it failed its XSD, specs/15-csm-gateway.md §6.1).
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
