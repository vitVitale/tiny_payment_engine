package com.tpe.paymentapi.application.verification;

/**
 * {@code 422 PAYEE_BANK_UNKNOWN} (specs/10-payment-api.md §3.4, §3.5): vop-service's directory has no bank for the
 * payee IBAN's bank code.
 */
public final class PayeeBankUnknownException extends RuntimeException {

    public PayeeBankUnknownException() {
        super("the payee's bank is not in the directory");
    }
}
