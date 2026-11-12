package com.tpe.vopservice.domain.matching;

import java.util.Objects;

/**
 * What the responder answers for one holder (specs/13-vop-service.md §3.2, §6.2).
 *
 * @param actualName the holder name as stored, for {@code CLOSE_MATCH} only, else {@code null}
 */
public record VerificationAnswer(VerificationResult result, String actualName) {

    public VerificationAnswer {
        Objects.requireNonNull(result, "result");
        if ((result == VerificationResult.CLOSE_MATCH) != (actualName != null)) {
            throw new IllegalArgumentException("actualName is set for CLOSE_MATCH and only for it");
        }
    }

    static VerificationAnswer of(VerificationResult result) {
        return new VerificationAnswer(result, null);
    }
}
