package com.tpe.contracts;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.RemittanceInformation;
import com.tpe.paymentdomain.StatusReason;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import java.time.Instant;

/**
 * Orchestrator → notification on {@code payments.events}: a payment reached {@code REJECTED} and any compensation is
 * done (specs/02-messaging-contracts.md §6.2). Incoming payments are rejected only after an answer was sent.
 */
@Contract(type = "PaymentRejected", version = 1)
public record PaymentRejected(
        PaymentId paymentId,
        Direction direction,
        Boolean onUs,
        TxId txId,
        Bic debtorAgentBic,
        Uetr uetr,
        EndToEndId endToEndId,
        @JsonUnwrapped Money amount,
        Party debtor,
        Party creditor,
        @OptionalField RemittanceInformation remittanceInformation,
        Instant acceptedAt,
        Instant rejectedAt,
        StatusReason reasonCode) implements Message {}
