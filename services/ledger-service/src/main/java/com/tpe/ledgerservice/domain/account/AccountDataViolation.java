package com.tpe.ledgerservice.domain.account;

/**
 * One invalid attribute of an account to open: its path ({@code customerId}, {@code holder.name}, …) and a field
 * code such as {@code REQUIRED} or {@code INVALID_CUSTOMER_ID}.
 */
public record AccountDataViolation(String field, String code) {}
