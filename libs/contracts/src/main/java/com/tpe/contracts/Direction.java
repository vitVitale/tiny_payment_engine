package com.tpe.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Direction of a payment from our bank's point of view (specs/02-messaging-contracts.md §1). A constant added later
 * is read as {@link #UNKNOWN} by consumers that do not know it yet (01 §4).
 */
public enum Direction {
    OUTGOING,
    INCOMING,
    /** Read for a constant this version does not know; never written by a producer. */
    UNKNOWN;

    @JsonCreator
    static Direction of(String name) {
        return Enums.orUnknown(Direction.class, name);
    }
}
