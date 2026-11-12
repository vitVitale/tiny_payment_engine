package com.tpe.contracts.vop;

/** The result of a payee verification (specs/13-vop-service.md §3.1, UC-02 4). */
public enum VopResult {
    MATCH,
    /** The name is close; the response carries the holder's actual name. */
    CLOSE_MATCH,
    NO_MATCH,
    /** The payee could not be verified; the payer may still proceed (UC-02 3a, 6a). */
    NOT_POSSIBLE
}
