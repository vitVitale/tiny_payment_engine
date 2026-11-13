package com.tpe.paymentorchestrator.application.risk;

import com.tpe.paymentdomain.StatusReason;
import java.util.Objects;

/** What risk-service said about a payment, or that it could not say (specs/11-payment-orchestrator.md §6.2). */
public sealed interface RiskAnswer {

    record Allow() implements RiskAnswer {}

    /** @param reason {@code FR01} for a fraud rule, {@code RR04} for a non-EU sanctions hit */
    record Deny(StatusReason reason) implements RiskAnswer {

        public Deny {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** No time for a manual review in an instant payment: treated as {@code DENY FR01} (UC-04 2b). */
    record Review() implements RiskAnswer {}

    /**
     * No decision: a technical failure.
     *
     * @param detail for the log; never a response body or exception message, which may carry personal data
     * @param retryable connect failure, read timeout, {@code 502}, {@code 503} or {@code 504}
     *     (specs/00-engineering-baseline.md §8); any other status or an unreadable answer is not, as asking again
     *     would get the same
     */
    record Unavailable(String detail, boolean retryable) implements RiskAnswer {}
}
