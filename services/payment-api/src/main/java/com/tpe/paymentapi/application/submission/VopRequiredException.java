package com.tpe.paymentapi.application.submission;

/**
 * {@code 422 VOP_REQUIRED} (specs/10-payment-api.md §5.3, UC-01 4a): the reference is missing, unknown, expired or
 * issued for another payment.
 */
public final class VopRequiredException extends RuntimeException {

    private final String problem;

    /** @param problem {@code UNKNOWN}, {@code EXPIRED}, {@code MISMATCH} or {@code MISSING} */
    public VopRequiredException(String problem) {
        super("VoP reference: " + problem);
        this.problem = problem;
    }

    public String problem() {
        return problem;
    }
}
