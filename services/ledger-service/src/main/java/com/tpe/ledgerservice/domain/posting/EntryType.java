package com.tpe.ledgerservice.domain.posting;

/** What a journal entry books (specs/12-ledger-service.md §5, §6.2). */
public enum EntryType {
    /** Settled outgoing payment: Dr payer / Cr CSM settlement account. */
    PAYMENT_OUT,
    /** Settled incoming payment: Dr CSM settlement account / Cr payee. */
    PAYMENT_IN,
    /** Payment between two of our customers: Dr payer / Cr payee. */
    ON_US,
    /** Test funding (UC-10 3): Dr FUNDING / Cr customer. */
    FUNDING
}
