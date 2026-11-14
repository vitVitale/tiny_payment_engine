package com.tpe.paymentapi.api.payment;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tpe.contracts.payment.PaymentState;
import com.tpe.contracts.payment.PaymentView;
import com.tpe.paymentapi.application.mapping.CustomerStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code 200} of {@code GET /v1/payments/{paymentId}} (specs/10-payment-api.md §3.2, UC-07): the customer status and
 * the payment as the payer sent it. {@code reasonCode} (for {@code RJCT}) and {@code finalisedAt} are written as
 * {@code null} when absent, as in the spec.
 */
public record PaymentStatusResponse(
        UUID paymentId,
        CustomerStatus status,
        @JsonInclude(JsonInclude.Include.ALWAYS) String reasonCode,
        PaymentState internalState,
        String endToEndId,
        Amount amount,
        Creditor creditor,
        Instant acceptedAt,
        @JsonInclude(JsonInclude.Include.ALWAYS) Instant finalisedAt) {

    /** @param value a decimal string such as {@code "123.45"} */
    public record Amount(String value, String currency) {}

    public record Creditor(String name, String iban) {}

    static PaymentStatusResponse of(PaymentView payment) {
        return new PaymentStatusResponse(payment.paymentId().value(), CustomerStatus.of(payment.state()),
                payment.reasonCode() == null ? null : payment.reasonCode().code(), payment.state(),
                payment.endToEndId().value(),
                new Amount(payment.amount().toDecimalString(), payment.amount().currency().getCurrencyCode()),
                new Creditor(payment.creditor().name().value(), payment.creditor().iban().value()),
                payment.acceptedAt(), payment.finalisedAt());
    }

    /** Leaves out the creditor, personal data (specs/26-observability.md §4.4). */
    @Override
    public String toString() {
        return "PaymentStatusResponse[paymentId=" + paymentId + ", status=" + status + ", internalState="
                + internalState + "]";
    }
}
