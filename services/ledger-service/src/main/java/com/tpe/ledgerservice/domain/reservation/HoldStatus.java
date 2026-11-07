package com.tpe.ledgerservice.domain.reservation;

/** The status of a hold (specs/12-ledger-service.md §5); {@code SETTLED} and {@code RELEASED} are final. */
public enum HoldStatus {
    ACTIVE,
    SETTLED,
    RELEASED
}
