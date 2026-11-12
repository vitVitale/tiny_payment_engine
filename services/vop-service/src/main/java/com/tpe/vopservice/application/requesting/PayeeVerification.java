package com.tpe.vopservice.application.requesting;

import com.tpe.paymentdomain.Iban;
import java.util.Objects;

/**
 * A payer's request to verify a payee (specs/13-vop-service.md §3.1, UC-02), validated.
 *
 * @param payeeOrganisationId {@code null} if none
 */
public record PayeeVerification(Iban debtorIban, Iban payeeIban, String payeeName, String payeeOrganisationId) {

    public PayeeVerification {
        Objects.requireNonNull(debtorIban, "debtorIban");
        Objects.requireNonNull(payeeIban, "payeeIban");
        Objects.requireNonNull(payeeName, "payeeName");
    }

    /** Leaves out IBANs and names, which are personal data (specs/26-observability.md §4.4). */
    @Override
    public String toString() {
        return "PayeeVerification[payeeIban=" + payeeIban.masked() + "]";
    }
}
