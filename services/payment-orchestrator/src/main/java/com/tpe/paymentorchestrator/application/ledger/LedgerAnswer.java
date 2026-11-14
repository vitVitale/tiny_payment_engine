package com.tpe.paymentorchestrator.application.ledger;

import java.util.Objects;

/**
 * What ledger-service did with a posting call (specs/12-ledger-service.md §3.1), or that it could not say.
 *
 * @param <T> what a done call returns
 */
public sealed interface LedgerAnswer<T> {

    /** The posting exists: created now or replayed for the same idempotency key. */
    record Done<T>(T value) implements LedgerAnswer<T> {

        public Done {
            Objects.requireNonNull(value, "value");
        }
    }

    /** A definitive refusal with the ledger's {@code code} (specs/12-ledger-service.md §3.4): asking again is futile. */
    record Refused<T>(LedgerRefusal refusal) implements LedgerAnswer<T> {

        public Refused {
            Objects.requireNonNull(refusal, "refusal");
        }
    }

    /**
     * No posting result: a technical failure.
     *
     * @param detail for the log; never a response body or exception message, which may carry personal data
     * @param retryable connect failure, read timeout, {@code 502}, {@code 503}, {@code 504} or
     *     {@code CONCURRENT_MODIFICATION} (specs/00-engineering-baseline.md §8, specs/12-ledger-service.md §3.4)
     */
    record Unavailable<T>(String detail, boolean retryable) implements LedgerAnswer<T> {}
}
