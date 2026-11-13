package com.tpe.paymentorchestrator.domain.payment;

/**
 * Why a payment was rejected, for {@code tpe.payment.rejected{cause}} and the {@code tpe.cause} span attribute
 * (specs/25-service-levels.md §1, §11; specs/26-observability.md §5.2): good or bad for the service levels.
 */
public enum RejectionCause {
    /** A business refusal: a fraud rule, insufficient funds, a blocked account. */
    BUSINESS,
    /** Our side failed: a neighbour timed out or was unavailable. */
    TECHNICAL,
    /** The scheme or the counterparty bank refused. */
    EXTERNAL
}
