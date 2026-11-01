package com.tpe.ledgerservice.domain;

/**
 * Business refusals of the ledger, the {@code code}s of specs/12-ledger-service.md §3.4. The HTTP status belongs to
 * the endpoint: {@code ACCOUNT_NOT_FOUND} is {@code 404} on a read and {@code 422} on a posting.
 */
public enum LedgerError {
    ACCOUNT_NOT_FOUND,
    /** A technical account where the operation needs a customer account. */
    ACCOUNT_NOT_CUSTOMER,
    /** A customer account without a customer number (Q-02). */
    CUSTOMER_ID_MISSING
}
