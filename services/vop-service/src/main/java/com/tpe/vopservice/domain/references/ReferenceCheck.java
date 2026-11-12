package com.tpe.vopservice.domain.references;

import com.tpe.vopservice.domain.matching.VerificationResult;

/**
 * The outcome of checking a {@code vopReference} (specs/13-vop-service.md §3.1): valid with the verification result,
 * or invalid with the problem.
 */
public record ReferenceCheck(boolean valid, VerificationResult result, ReferenceProblem problem) {

    public ReferenceCheck {
        if (valid != (result != null) || valid == (problem != null)) {
            throw new IllegalArgumentException("a valid check has a result, an invalid one a problem");
        }
    }

    public static ReferenceCheck valid(VerificationResult result) {
        return new ReferenceCheck(true, result, null);
    }

    public static ReferenceCheck invalid(ReferenceProblem problem) {
        return new ReferenceCheck(false, null, problem);
    }
}
