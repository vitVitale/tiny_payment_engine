package com.tpe.vopservice.domain.references;

/** Why a {@code vopReference} does not cover a payment (specs/13-vop-service.md §3.1, UC-01 4a). */
public enum ReferenceProblem {
    /** No such reference, or it has been deleted by retention. */
    UNKNOWN,
    /** Past its {@code expires_at}. */
    EXPIRED,
    /** Issued for another debtor IBAN, payee IBAN or payee name. */
    MISMATCH
}
