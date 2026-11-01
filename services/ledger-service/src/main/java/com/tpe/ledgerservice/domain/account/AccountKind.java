package com.tpe.ledgerservice.domain.account;

/** Customer accounts are published and balance-checked; technical ones (ADR-005) are neither. */
public enum AccountKind {
    CUSTOMER,
    TECHNICAL
}
