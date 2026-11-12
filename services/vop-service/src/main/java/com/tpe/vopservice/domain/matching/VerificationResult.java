package com.tpe.vopservice.domain.matching;

/** The answer to a payee verification (specs/13-vop-service.md §3, UC-02 4, UC-03 4). */
public enum VerificationResult {
    MATCH,
    /** The name is close; the answer carries the holder's actual name. */
    CLOSE_MATCH,
    NO_MATCH,
    /** The account is unknown or cannot be verified (UC-03 2a); says nothing about whether it exists. */
    NOT_POSSIBLE
}
