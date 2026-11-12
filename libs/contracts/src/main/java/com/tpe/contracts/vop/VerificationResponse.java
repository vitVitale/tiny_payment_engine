package com.tpe.contracts.vop;

import com.tpe.paymentdomain.Bic;
import java.time.Instant;

/**
 * {@code 201} of {@code POST /v1/verifications} (specs/13-vop-service.md §3.1): the reference payment-api accepts until
 * {@code expiresAt}, for the request's debtor IBAN, payee IBAN and payee name only.
 *
 * @param actualName the holder name as answered, for {@code CLOSE_MATCH} only, else {@code null}
 * @param respondedBy the BIC of the responding PSP the request was routed to: ours for an own customer; for a
 *     {@code NOT_POSSIBLE} because that PSP could not be reached, still its BIC
 */
public record VerificationResponse(
        String vopReference, VopResult result, String actualName, Instant expiresAt, Bic respondedBy) {

    @Override
    public String toString() {
        return "VerificationResponse[vopReference=" + vopReference + ", result=" + result + ", expiresAt="
                + expiresAt + ", respondedBy=" + respondedBy + "]";
    }
}
