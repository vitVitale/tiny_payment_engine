package com.tpe.ledgerservice.domain.account;

/**
 * Why an account is blocked (specs/12-ledger-service.md §3.2); blocks are kept per reason. The sanctions reasons may be
 * added or lifted only by risk-service or a compliance officer.
 */
public enum BlockReason {
    SANCTIONS_PROVISIONAL,
    SANCTIONS,
    OPERATOR;

    /** {@code SANCTIONS} or {@code SANCTIONS_PROVISIONAL}: protected from back-office operators. */
    public boolean sanctions() {
        return this != OPERATOR;
    }
}
