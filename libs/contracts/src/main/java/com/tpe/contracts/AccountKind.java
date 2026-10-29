package com.tpe.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Kind of a ledger account; technical accounts are never published (02 §5). A constant added later is read as {@link
 * #UNKNOWN} (01 §4).
 */
public enum AccountKind {
    CUSTOMER,
    TECHNICAL,
    /** Read for a constant this version does not know; never written by a producer. */
    UNKNOWN;

    @JsonCreator
    static AccountKind of(String name) {
        return Enums.orUnknown(AccountKind.class, name);
    }
}
