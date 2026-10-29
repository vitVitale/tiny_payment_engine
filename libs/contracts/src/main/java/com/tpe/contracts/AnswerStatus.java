package com.tpe.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * The answer the gateway sent to a received credit transfer (specs/02-messaging-contracts.md §4). A constant added
 * later is read as {@link #UNKNOWN} (01 §4).
 */
public enum AnswerStatus {
    ACCP,
    RJCT,
    /** Read for a constant this version does not know; never written by a producer. */
    UNKNOWN;

    @JsonCreator
    static AnswerStatus of(String name) {
        return Enums.orUnknown(AnswerStatus.class, name);
    }
}
