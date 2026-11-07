package com.tpe.ledgerservice.domain;

/**
 * Business refusals of the ledger, the {@code code}s of specs/12-ledger-service.md §3.4. The HTTP status belongs to
 * the endpoint: {@code ACCOUNT_NOT_FOUND} is {@code 404} on a read and {@code 422} on a posting.
 */
public enum LedgerError {
    ACCOUNT_NOT_FOUND,
    /** A technical account where the operation needs a customer account. */
    ACCOUNT_NOT_CUSTOMER,
    ACCOUNT_CLOSED,
    /** A back-office operator adding or lifting a {@code SANCTIONS*} block (§3.2); 403. */
    SANCTIONS_BLOCK_PROTECTED,
    /** The caller may not touch blocks of this reason at all, e.g. risk-service and {@code OPERATOR}; 403. */
    FORBIDDEN,
    /** A customer account without a customer number (Q-02). */
    CUSTOMER_ID_MISSING,
    /** A debit beyond the available balance. */
    INSUFFICIENT_FUNDS,
    /** A currency other than EUR (A3); defensive. */
    CURRENCY_NOT_SUPPORTED,
    /** An {@code Idempotency-Key} reused for a different request (§6.1); a caller bug. */
    IDEMPOTENCY_CONFLICT,
    /** The account kept changing under the optimistic lock after every internal retry (§6.2); the caller retries. */
    CONCURRENT_MODIFICATION
}
