package com.tpe.paymentapi.application.validation;

/**
 * An entry of a Problem Details {@code errors} list (specs/00-engineering-baseline.md §7): the JSON path of the field
 * and one of the field codes of specs/10-payment-api.md §3.5; never the rejected value.
 */
public record FieldError(String field, String code) {

    public static final String REQUIRED = "REQUIRED";
    public static final String SAME_DEBTOR_AND_CREDITOR = "SAME_DEBTOR_AND_CREDITOR";
    public static final String INVALID_BIC = "INVALID_BIC";
    public static final String INVALID_AMOUNT = "INVALID_AMOUNT";
    public static final String UNSUPPORTED_CURRENCY = "UNSUPPORTED_CURRENCY";
}
