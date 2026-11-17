package com.tpe.paymentorchestrator.application.cases;

import java.util.UUID;

/**
 * The case is {@code RESOLVED} already: {@code 409 CASE_ALREADY_RESOLVED}.
 */
public class CaseAlreadyResolvedException extends RuntimeException {

    CaseAlreadyResolvedException(UUID caseId) {
        super("case " + caseId + " is resolved already");
    }
}
