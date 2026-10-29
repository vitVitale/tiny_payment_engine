package com.tpe.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Ledger → vop, risk on the compacted {@code accounts.events}: a lifecycle change of a customer account, carrying the
 * full current snapshot so a projection can be rebuilt from the topic (specs/02-messaging-contracts.md §5, ADR-007).
 * {@code revision} starts at 1 and increases with every lifecycle change; consumers ignore an event whose revision is
 * not newer than the stored one.
 */
public sealed interface AccountEvent extends Message
        permits AccountOpened, AccountBlocked, AccountUnblocked, AccountClosed {

    /** The ledger account id, also the Kafka key. */
    UUID accountId();

    Long revision();

    Instant occurredAt();

    AccountSnapshot account();
}
