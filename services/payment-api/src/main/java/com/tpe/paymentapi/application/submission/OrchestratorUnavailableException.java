package com.tpe.paymentapi.application.submission;

/**
 * No answer from payment-orchestrator: {@code 503 ORCHESTRATOR_UNAVAILABLE} with {@code Retry-After}
 * (specs/10-payment-api.md §3.1 step 7, UC-01 5a).
 */
public final class OrchestratorUnavailableException extends RuntimeException {

    public OrchestratorUnavailableException(String message) {
        super(message);
    }
}
