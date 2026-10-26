package com.tpe.paymentdomain;

/** Machine-readable reason a value was rejected; the field error codes of specs/10-payment-api.md §4. */
public enum ValidationCode {
    INVALID_AMOUNT,
    UNSUPPORTED_CURRENCY,
    INVALID_IBAN,
    INVALID_BIC,
    INVALID_CHARACTERS
}
