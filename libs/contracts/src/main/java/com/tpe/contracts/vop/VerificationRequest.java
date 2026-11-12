package com.tpe.contracts.vop;

import com.tpe.paymentdomain.Iban;

/**
 * {@code POST /v1/verifications} (specs/13-vop-service.md §3.1, UC-02): verify {@code payeeName} as the holder of
 * {@code payeeIban} for a payment from {@code debtorIban}.
 *
 * @param payeeOrganisationId {@code null} if the payer gave none
 */
public record VerificationRequest(Iban debtorIban, Iban payeeIban, String payeeName, String payeeOrganisationId) {

    /** Leaves out IBANs and the name, which are personal data (specs/26-observability.md §4.4). */
    @Override
    public String toString() {
        return "VerificationRequest[payeeIban=" + (payeeIban == null ? null : payeeIban.masked()) + "]";
    }
}
