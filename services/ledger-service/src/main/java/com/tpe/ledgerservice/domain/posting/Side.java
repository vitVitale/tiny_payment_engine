package com.tpe.ledgerservice.domain.posting;

/** The side of a posting (specs/12-ledger-service.md §5); a credit raises a customer account's balance. */
public enum Side {
    DR,
    CR
}
