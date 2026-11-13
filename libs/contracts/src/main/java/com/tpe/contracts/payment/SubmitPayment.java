package com.tpe.contracts.payment;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.tpe.contracts.vop.VopResult;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.RemittanceInformation;
import java.util.UUID;

/**
 * {@code POST /v1/payments} (specs/11-payment-orchestrator.md §3.1): payment-api submits a validated outgoing payment.
 *
 * @param commandId derived from the client and its {@code Idempotency-Key}; the same id is the same payment
 * @param amount written as {@code amountMinor} and {@code currency}
 * @param remittanceInformation absent when the payer gave none
 * @param vopResult the result of payment-api's reference check, never the client's
 */
public record SubmitPayment(
        UUID commandId,
        String clientId,
        EndToEndId endToEndId,
        Debtor debtor,
        Creditor creditor,
        @JsonUnwrapped Money amount,
        @JsonInclude(JsonInclude.Include.NON_NULL) RemittanceInformation remittanceInformation,
        String vopReference,
        VopResult vopResult) {

    public record Debtor(PartyName name, Iban iban) {}

    /** @param bic absent: the orchestrator takes it from the bank directory */
    public record Creditor(PartyName name, Iban iban, @JsonInclude(JsonInclude.Include.NON_NULL) Bic bic) {}

    /** Leaves out the parties and the remittance text, which are personal data (specs/26-observability.md §4.4). */
    @Override
    public String toString() {
        return "SubmitPayment[commandId=" + commandId + ", clientId=" + clientId + ", amount=" + amount + "]";
    }
}
