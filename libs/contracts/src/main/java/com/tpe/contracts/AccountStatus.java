package com.tpe.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Status of a ledger account (specs/12-ledger-service.md §5); {@code BLOCKED} while any block is active. A constant
 * added later is read as {@link #UNKNOWN} (01 §4).
 */
public enum AccountStatus {
    OPEN,
    BLOCKED,
    CLOSED,
    /** Read for a constant this version does not know; never written by a producer. */
    UNKNOWN;

    @JsonCreator
    static AccountStatus of(String name) {
        return Enums.orUnknown(AccountStatus.class, name);
    }
}
