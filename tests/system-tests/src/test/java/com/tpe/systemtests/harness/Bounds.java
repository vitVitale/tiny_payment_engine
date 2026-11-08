package com.tpe.systemtests.harness;

import com.tpe.messaging.outbox.OutboxRelay;
import java.time.Duration;

/**
 * Awaitility upper bounds derived from the services' configuration in the {@code systemtest} profile, never magic
 * numbers (specs/20-testing-strategy.md §2).
 */
public final class Bounds {

    /** What the harness adds for its own side: a Kafka poll, an HTTP round trip, scheduling on a busy CI runner. */
    private static final Duration HARNESS = Duration.ofSeconds(2);

    private Bounds() {}

    /** {@link #eventDelivery(ServiceProfile)} of ledger-service as the harness runs it. */
    public static Duration eventDelivery() {
        return eventDelivery(ServiceProfile.ledger());
    }

    /**
     * From a committed lifecycle change to its record at a consumer: the outbox relay of 01 §3.3 picks it up after the
     * visibility window, within one poll, or after its longest backoff when a send failed; a relay that has not led
     * yet takes over within one takeover retry. The two the service may configure come from {@code profile}, the rest
     * are the relay's fixed defaults.
     */
    static Duration eventDelivery(ServiceProfile profile) {
        OutboxRelay.Settings relay = OutboxRelay.Settings.defaults("ledger-service");
        return profile.duration("tpe.messaging.outbox.takeover-retry").orElse(relay.takeoverRetry())
                .plus(profile.duration("tpe.messaging.outbox.visibility-window").orElse(relay.visibilityWindow()))
                .plus(relay.pollTimeout())
                .plus(relay.maxBackoff())
                .plus(HARNESS);
    }
}
