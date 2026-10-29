package com.tpe.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Reason of an account block (specs/12-ledger-service.md §3.2); blocks are kept per reason. A constant added later is
 * read as {@link #UNKNOWN} (01 §4).
 */
public enum BlockReason {
    SANCTIONS_PROVISIONAL,
    SANCTIONS,
    OPERATOR,
    /** Read for a constant this version does not know; never written by a producer. */
    UNKNOWN;

    @JsonCreator
    static BlockReason of(String name) {
        return Enums.orUnknown(BlockReason.class, name);
    }
}
