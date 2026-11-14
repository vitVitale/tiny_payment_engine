package com.tpe.paymentapi.application.submission;

/**
 * A downstream service answered what its contract does not allow (a {@code 4xx} for a valid request, an
 * unreadable body): a bug on one side, {@code 500}.
 */
public final class UnexpectedAnswerException extends RuntimeException {

    public UnexpectedAnswerException(String message) {
        super(message);
    }
}
