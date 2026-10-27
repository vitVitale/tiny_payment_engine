package com.tpe.paymentdomain;

/**
 * Machine-readable reason a value was rejected: the field error codes of specs/10-payment-api.md §3.5, plus
 * {@link #INVALID_UETR}, {@link #INVALID_REASON_CODE} and {@link #INVALID_BANK_DIRECTORY}.
 */
public enum ValidationCode {
    INVALID_AMOUNT,
    UNSUPPORTED_CURRENCY,
    INVALID_IBAN,
    INVALID_BIC,
    INVALID_CHARACTERS,
    TOO_LONG,
    REQUIRED,
    INVALID_UETR,
    /** A {@link ReasonCode.Other} built from a code that has a {@link ReasonCode} constant. */
    INVALID_REASON_CODE,
    /** Bank directory configuration that breaks a rule of specs/01-shared-libraries.md §1.4. */
    INVALID_BANK_DIRECTORY
}
