package com.tpe.paymentapi.api.security;

/** The debtor IBAN is not in the token's {@code accounts}: {@code 403 DEBTOR_ACCOUNT_NOT_OWNED} (UC-01 1a). */
public final class DebtorAccountNotOwnedException extends RuntimeException {

    public DebtorAccountNotOwnedException() {
        super("the debtor account is not one of the token's accounts");
    }
}
