package com.tpe.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Type of an account holder (specs/12-ledger-service.md §3.2). A constant added later is read as {@link #UNKNOWN} (01
 * §4).
 */
public enum HolderType {
    PERSON,
    ORGANISATION,
    /** Read for a constant this version does not know; never written by a producer. */
    UNKNOWN;

    @JsonCreator
    static HolderType of(String name) {
        return Enums.orUnknown(HolderType.class, name);
    }
}
