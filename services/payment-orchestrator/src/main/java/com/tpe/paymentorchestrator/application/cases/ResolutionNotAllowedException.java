package com.tpe.paymentorchestrator.application.cases;

import com.tpe.paymentorchestrator.domain.payment.CaseResolution;
import com.tpe.paymentorchestrator.domain.payment.ExceptionCaseType;

/**
 * The resolution does not resolve a case of this type (specs/11-payment-orchestrator.md §3.2):
 * {@code 422 RESOLUTION_NOT_ALLOWED}.
 */
public class ResolutionNotAllowedException extends RuntimeException {

    ResolutionNotAllowedException(ExceptionCaseType type, CaseResolution resolution) {
        super(resolution + " does not resolve a " + type + " case");
    }
}
