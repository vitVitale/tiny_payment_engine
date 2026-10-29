package com.tpe.contracts;

import java.time.Instant;
import java.util.UUID;

/** A block was lifted; the status stays {@code BLOCKED} while other blocks remain (02 §5). */
@Contract(type = "AccountUnblocked", version = 1)
public record AccountUnblocked(
        UUID accountId,
        Long revision,
        Instant occurredAt,
        AccountSnapshot account,
        BlockReason liftedReason) implements AccountEvent {}
