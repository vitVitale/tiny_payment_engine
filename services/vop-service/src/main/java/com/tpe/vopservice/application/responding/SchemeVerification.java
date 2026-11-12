package com.tpe.vopservice.application.responding;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.Iban;
import java.util.Objects;

/**
 * A verification request of another PSP (specs/13-vop-service.md §3.2, UC-03), its participant authenticated.
 *
 * @param organisationId {@code null} if the request has none
 */
public record SchemeVerification(String requestId, Bic requestingPsp, Iban iban, String name, String organisationId) {

    public SchemeVerification {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(requestingPsp, "requestingPsp");
        Objects.requireNonNull(iban, "iban");
        Objects.requireNonNull(name, "name");
    }

    /** Leaves out the IBAN and the name, which are personal data (specs/26-observability.md §4.4). */
    @Override
    public String toString() {
        return "SchemeVerification[requestId=" + requestId + ", requestingPsp=" + requestingPsp.value() + "]";
    }
}
