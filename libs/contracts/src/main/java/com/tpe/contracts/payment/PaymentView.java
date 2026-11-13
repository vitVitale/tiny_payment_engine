package com.tpe.contracts.payment;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.tpe.contracts.Direction;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.RemittanceInformation;
import com.tpe.paymentdomain.StatusReason;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import java.time.Instant;

/**
 * {@code 200} of {@code GET /v1/payments/{paymentId}} (specs/11-payment-orchestrator.md §3.1). {@code reasonCode},
 * {@code remittanceInformation} and {@code finalisedAt} are written as {@code null} when absent, as in the spec.
 * payment-api checks ownership and maps the state (UC-07).
 *
 * @param amount written as {@code amountMinor} and {@code currency}
 */
public record PaymentView(
        PaymentId paymentId,
        Direction direction,
        boolean onUs,
        PaymentState state,
        @JsonInclude(JsonInclude.Include.ALWAYS) StatusReason reasonCode,
        TxId txId,
        Uetr uetr,
        EndToEndId endToEndId,
        @JsonUnwrapped Money amount,
        Holder debtor,
        Holder creditor,
        @JsonInclude(JsonInclude.Include.ALWAYS) RemittanceInformation remittanceInformation,
        Instant acceptedAt,
        @JsonInclude(JsonInclude.Include.ALWAYS) Instant finalisedAt) {

    /** A party of the payment as the view shows it. */
    public record Holder(PartyName name, Iban iban) {}

    /** Leaves out the parties and the remittance text, which are personal data (specs/26-observability.md §4.4). */
    @Override
    public String toString() {
        return "PaymentView[paymentId=" + paymentId + ", state=" + state + ", amount=" + amount + "]";
    }
}
