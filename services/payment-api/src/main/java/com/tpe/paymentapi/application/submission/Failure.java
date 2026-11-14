package com.tpe.paymentapi.application.submission;

/**
 * Why a downstream service gave no answer; which of them the caller retries is its rule
 * (specs/10-payment-api.md §4, specs/00-engineering-baseline.md §8).
 */
public enum Failure {
    /** Not reached: the request was not sent. */
    CONNECT,
    /** Sent, but no answer within the read timeout. */
    TIMEOUT,
    /** {@code 502}, {@code 503} or {@code 504}. */
    GATEWAY,
    /** Any other {@code 5xx}. */
    SERVER_ERROR
}
