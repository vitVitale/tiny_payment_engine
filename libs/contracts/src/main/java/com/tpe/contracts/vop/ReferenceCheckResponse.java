package com.tpe.contracts.vop;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code 200} of {@code POST /v1/verifications/{vopReference}/check} (specs/13-vop-service.md §3.1): valid with the
 * verification result, or not valid with the problem; the member that does not apply is left out.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReferenceCheckResponse(boolean valid, VopResult result, VopReferenceProblem problem) {

    public static ReferenceCheckResponse valid(VopResult result) {
        return new ReferenceCheckResponse(true, result, null);
    }

    public static ReferenceCheckResponse invalid(VopReferenceProblem problem) {
        return new ReferenceCheckResponse(false, null, problem);
    }
}
