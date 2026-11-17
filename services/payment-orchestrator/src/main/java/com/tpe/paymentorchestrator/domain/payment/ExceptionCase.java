package com.tpe.paymentorchestrator.domain.payment;

import com.tpe.paymentdomain.PaymentId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A row of {@code exception_cases} (specs/11-payment-orchestrator.md §3.2, §5): something an operator has to look at.
 *
 * @param paymentId {@code null} for a case without a payment (a message for no known payment, §6.5)
 * @param details what the case is about; never personal data
 * @param resolution {@code null} while {@code OPEN}, as are {@code resolvedBy}, {@code resolvedAt} and
 *     {@code comment}
 * @param resolvedBy the operator's {@code sub}
 */
public record ExceptionCase(
        UUID caseId,
        PaymentId paymentId,
        ExceptionCaseType type,
        Status status,
        Map<String, Object> details,
        Instant openedAt,
        CaseResolution resolution,
        String resolvedBy,
        Instant resolvedAt,
        String comment) {

    public enum Status {
        OPEN,
        RESOLVED
    }

    public ExceptionCase {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        details = Map.copyOf(Objects.requireNonNull(details, "details"));
        Objects.requireNonNull(openedAt, "openedAt");
    }
}
