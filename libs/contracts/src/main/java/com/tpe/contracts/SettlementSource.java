package com.tpe.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Where the gateway learned the final outcome: a status report or an investigation (02 §4). A constant added later is
 * read as {@link #UNKNOWN} (01 §4).
 */
public enum SettlementSource {
    STATUS_REPORT,
    INVESTIGATION,
    /** Read for a constant this version does not know; never written by a producer. */
    UNKNOWN;

    @JsonCreator
    static SettlementSource of(String name) {
        return Enums.orUnknown(SettlementSource.class, name);
    }
}
