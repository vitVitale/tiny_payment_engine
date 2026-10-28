package com.tpe.contracts;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.RemittanceInformation;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import java.time.Instant;

/**
 * Orchestrator → gateway on {@code csm.outbound.commands}: send this outgoing credit transfer
 * (specs/02-messaging-contracts.md §3.1). {@code amount} is written as {@code amountMinor} + {@code currency}.
 * Invariant: {@code acceptedAt < sendBy < deadlineAt}.
 */
@Contract(type = "SendCreditTransfer", version = 1)
public record SendCreditTransfer(
        PaymentId paymentId,
        TxId txId,
        Uetr uetr,
        EndToEndId endToEndId,
        Instant acceptedAt,
        Instant sendBy,
        Instant deadlineAt,
        @JsonUnwrapped Money amount,
        Party debtor,
        Bic debtorAgentBic,
        Party creditor,
        Bic creditorAgentBic,
        @OptionalField RemittanceInformation remittanceInformation) implements Message {}
