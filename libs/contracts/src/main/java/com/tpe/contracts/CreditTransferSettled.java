package com.tpe.contracts;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.TxId;
import java.time.Instant;

/**
 * Gateway → orchestrator on {@code csm.inbound.events}: the CSM settled a credit transfer, outgoing or incoming
 * (specs/02-messaging-contracts.md §4). {@code paymentId} is present exactly on {@code OUTGOING}.
 */
@Contract(type = "CreditTransferSettled", version = 1)
public record CreditTransferSettled(
        TxId txId,
        Bic debtorAgentBic,
        Direction direction,
        @OptionalField PaymentId paymentId,
        Instant settledAt,
        SettlementSource source) implements Message {}
