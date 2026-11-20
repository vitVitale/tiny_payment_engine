package com.tpe.paymentorchestrator.infrastructure.http;

import java.io.IOException;
import org.springframework.web.client.RestClientException;

/**
 * Tells an answer cut off in transit, by a read timeout or a lost connection, from a body read whole that makes no
 * answer (specs/00-engineering-baseline.md §8): the first is retryable as any read timeout, the second is not.
 */
public final class CutOffAnswers {

    private CutOffAnswers() {}

    /** {@code e}, raised reading an answer, comes from an I/O failure. */
    public static boolean cutOff(RestClientException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }
}
