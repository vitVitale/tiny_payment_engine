package com.tpe.contracts.vop;

/** Why a {@code vopReference} does not cover a payment (specs/13-vop-service.md §3.1, UC-01 4a). */
public enum VopReferenceProblem {
    /** Past its expiry. */
    EXPIRED,
    /** Issued for another debtor IBAN, payee IBAN or payee name. */
    MISMATCH,
    /** No such reference. */
    UNKNOWN
}
