package com.tpe.vopservice.api.responder;

import com.tpe.vopservice.domain.matching.VerificationAnswer;
import com.tpe.vopservice.domain.matching.VerificationResult;

/**
 * The answer of {@code POST /vop/v1/verifications} (specs/13-vop-service.md §3.2).
 *
 * @param actualName the holder name as stored, for {@code CLOSE_MATCH} only, else {@code null}
 */
record SchemeVerificationResponse(String requestId, VerificationResult result, String actualName) {

    static SchemeVerificationResponse of(String requestId, VerificationAnswer answer) {
        return new SchemeVerificationResponse(requestId, answer.result(), answer.actualName());
    }
}
