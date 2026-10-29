package com.tpe.contracts;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.StatusReason;
import com.tpe.paymentdomain.TxId;
import java.time.Instant;

/**
 * Gateway → orchestrator on {@code csm.inbound.events}: the gateway answered an incoming credit transfer
 * (specs/02-messaging-contracts.md §4). Always {@code INCOMING}; {@code reasonCode} is present exactly on
 * {@code RJCT}.
 */
@Contract(type = "AnswerSent", version = 1)
public record AnswerSent(
        TxId txId,
        Bic debtorAgentBic,
        Direction direction,
        AnswerStatus status,
        @OptionalField StatusReason reasonCode,
        DecidedBy decidedBy,
        Instant sentAt) implements Message {}
