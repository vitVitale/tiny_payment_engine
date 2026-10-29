package com.tpe.contracts;

import java.time.Instant;
import java.util.UUID;

/** An account was opened (specs/02-messaging-contracts.md §5). */
@Contract(type = "AccountOpened", version = 1)
public record AccountOpened(
        UUID accountId,
        Long revision,
        Instant occurredAt,
        AccountSnapshot account) implements AccountEvent {}
