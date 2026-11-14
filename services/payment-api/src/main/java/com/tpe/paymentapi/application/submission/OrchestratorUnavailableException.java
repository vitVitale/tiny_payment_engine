package com.tpe.paymentapi.application.submission;

import com.tpe.contracts.vop.VopResult;

/**
 * No answer from payment-orchestrator: {@code 503 ORCHESTRATOR_UNAVAILABLE} with {@code Retry-After}
 * (specs/10-payment-api.md §3.1 step 7, UC-01 5a).
 */
public final class OrchestratorUnavailableException extends RuntimeException {

    private final transient VopResult submittedWith;

    /** Nothing was submitted, or what was certainly did not arrive. */
    public OrchestratorUnavailableException(String message) {
        this(message, null);
    }

    /**
     * @param submittedWith the {@code vopResult} of a submit that may have reached the orchestrator (a timeout or a
     *     {@code 5xx}, UC-01 6a); {@code null} when it certainly did not
     */
    public OrchestratorUnavailableException(String message, VopResult submittedWith) {
        super(message);
        this.submittedWith = submittedWith;
    }

    /** The {@code vopResult} of a submit that may have arrived; {@code null} when none may have. */
    public VopResult submittedWith() {
        return submittedWith;
    }
}
