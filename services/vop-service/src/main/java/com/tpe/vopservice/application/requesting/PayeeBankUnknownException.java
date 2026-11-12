package com.tpe.vopservice.application.requesting;

/** The payee IBAN's bank is not in the directory: {@code 422 PAYEE_BANK_UNKNOWN} (specs/13-vop-service.md §3.1). */
public final class PayeeBankUnknownException extends RuntimeException {

    public PayeeBankUnknownException() {
        super("the payee's bank is not in the bank directory");
    }
}
