package com.tpe.observability.payment;

/** The {@code tpe.direction} span attribute: from our bank's point of view (specs/26-observability.md §5.3). */
public enum PaymentDirection {
    OUTGOING,
    INCOMING
}
