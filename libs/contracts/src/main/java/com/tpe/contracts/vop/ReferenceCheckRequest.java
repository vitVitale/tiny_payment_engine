package com.tpe.contracts.vop;

import com.tpe.paymentdomain.Iban;

/**
 * {@code POST /v1/verifications/{vopReference}/check} (specs/13-vop-service.md §3.1, UC-01 4): the values of the
 * payment that quotes the reference.
 */
public record ReferenceCheckRequest(Iban debtorIban, Iban payeeIban, String payeeName) {

    /** Leaves out IBANs and the name, which are personal data (specs/26-observability.md §4.4). */
    @Override
    public String toString() {
        return "ReferenceCheckRequest[payeeIban=" + (payeeIban == null ? null : payeeIban.masked()) + "]";
    }
}
