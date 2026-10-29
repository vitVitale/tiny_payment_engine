package com.tpe.contracts;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.RemittanceInformation;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Gateway → orchestrator on {@code csm.inbound.events}: a schema-valid, non-duplicate incoming {@code pacs.008} was
 * received (specs/02-messaging-contracts.md §4). Always {@code INCOMING}; {@code receivedAt < answerDeadline}.
 */
@Contract(type = "CreditTransferReceived", version = 1)
public record CreditTransferReceived(
        TxId txId,
        Bic debtorAgentBic,
        Direction direction,
        MsgId msgId,
        Uetr uetr,
        EndToEndId endToEndId,
        Instant receivedAt,
        Instant answerDeadline,
        Instant acceptanceDateTime,
        LocalDate interbankSettlementDate,
        @JsonUnwrapped Money amount,
        Party debtor,
        Party creditor,
        Bic creditorAgentBic,
        @OptionalField RemittanceInformation remittanceInformation) implements Message {}
