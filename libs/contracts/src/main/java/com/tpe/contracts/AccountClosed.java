package com.tpe.contracts;

import java.time.Instant;
import java.util.UUID;

/** An account was closed (specs/02-messaging-contracts.md §5). */
@Contract(type = "AccountClosed", version = 1)
public record AccountClosed(
        UUID accountId,
        Long revision,
        Instant occurredAt,
        AccountSnapshot account) implements AccountEvent {}
