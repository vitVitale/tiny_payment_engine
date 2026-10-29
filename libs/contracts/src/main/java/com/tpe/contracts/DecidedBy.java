package com.tpe.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * What decided the answer: the orchestrator's verdict, or the answer deadline passing (02 §4). A constant added later
 * is read as {@link #UNKNOWN} (01 §4).
 */
public enum DecidedBy {
    VERDICT,
    DEADLINE,
    /** Read for a constant this version does not know; never written by a producer. */
    UNKNOWN;

    @JsonCreator
    static DecidedBy of(String name) {
        return Enums.orUnknown(DecidedBy.class, name);
    }
}
