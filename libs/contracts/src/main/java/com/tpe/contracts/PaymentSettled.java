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
import java.util.UUID;

/**
 * Orchestrator → notification on {@code payments.events}: a payment reached {@code SETTLED} and its ledger entry is
 * posted (specs/02-messaging-contracts.md §6.1). {@code creditor.accountId} is set for incoming and on-us payments;
 * {@code bookingEntryId} is the ledger journal entry id.
 */
@Contract(type = "PaymentSettled", version = 1)
public record PaymentSettled(
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
        Instant settledAt,
        UUID bookingEntryId) implements Message {}
