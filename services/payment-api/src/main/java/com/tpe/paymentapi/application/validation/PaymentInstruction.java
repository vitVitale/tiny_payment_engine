package com.tpe.paymentapi.application.validation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * A payment as the channel sent it (specs/10-payment-api.md §3.1): the JSON body, and the values the XML mapper reads
 * from a {@code pain.001}. Only presence is checked here, by Bean Validation with the field code as message; the
 * value rules are the {@code payment-domain} value objects' ({@link PaymentValidator}).
 *
 * @param remittanceInformation optional; blank is absent
 * @param vopReference a missing one is {@code 422 VOP_REQUIRED} (UC-01 4a), not a field error
 */
public record PaymentInstruction(
        @NotBlank(message = FieldError.REQUIRED) String endToEndId,
        @NotNull(message = FieldError.REQUIRED) @Valid Debtor debtor,
        @NotNull(message = FieldError.REQUIRED) @Valid Creditor creditor,
        @NotNull(message = FieldError.REQUIRED) @Valid Amount amount,
        String remittanceInformation,
        String vopReference) {

    public record Debtor(
            @NotBlank(message = FieldError.REQUIRED) String name,
            @NotBlank(message = FieldError.REQUIRED) String iban) {

        @Override
        public String toString() {
            return "Debtor[…]";
        }
    }

    /** @param bic optional: the directory's BIC for the IBAN's bank code is used when absent */
    public record Creditor(
            @NotBlank(message = FieldError.REQUIRED) String name,
            @NotBlank(message = FieldError.REQUIRED) String iban,
            String bic) {

        @Override
        public String toString() {
            return "Creditor[bic=" + bic + "]";
        }
    }

    /** @param value a decimal string such as {@code "123.45"} */
    public record Amount(
            @NotBlank(message = FieldError.REQUIRED) String value,
            @NotBlank(message = FieldError.REQUIRED) String currency) {}

    /** Leaves out the parties and the remittance text, which are personal data (specs/26-observability.md §4.4). */
    @Override
    public String toString() {
        return "PaymentInstruction[amount=" + amount + ", vopReference=" + vopReference + "]";
    }
}
