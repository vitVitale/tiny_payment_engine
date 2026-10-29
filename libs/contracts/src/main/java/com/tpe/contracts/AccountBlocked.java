package com.tpe.contracts;

import java.time.Instant;
import java.util.UUID;

/** A block was added to an account (specs/02-messaging-contracts.md §5). */
@Contract(type = "AccountBlocked", version = 1)
public record AccountBlocked(
        UUID accountId,
        Long revision,
        Instant occurredAt,
        AccountSnapshot account,
        BlockReason blockReason) implements AccountEvent {}
