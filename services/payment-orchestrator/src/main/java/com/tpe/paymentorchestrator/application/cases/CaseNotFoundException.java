package com.tpe.paymentorchestrator.application.cases;

import java.util.UUID;

/**
 * No exception case has this id: {@code 404 CASE_NOT_FOUND}.
 */
public class CaseNotFoundException extends RuntimeException {

    public CaseNotFoundException(UUID caseId) {
        super("no exception case " + caseId);
    }
}
