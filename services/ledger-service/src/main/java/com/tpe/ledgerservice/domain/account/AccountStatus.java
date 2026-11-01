package com.tpe.ledgerservice.domain.account;

/** {@code BLOCKED} while at least one block is active (12 §3.2). */
public enum AccountStatus {
    OPEN,
    BLOCKED,
    CLOSED
}
