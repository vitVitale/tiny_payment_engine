package com.tpe.ledgerservice.domain.reservation;

/** The status of a pending credit (specs/12-ledger-service.md §5); {@code CONFIRMED} and {@code CANCELLED} are final. */
public enum PendingCreditStatus {
    ACTIVE,
    CONFIRMED,
    CANCELLED
}
