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

    /**
     * From a {@code 202} to the payment's final state and its final event at a consumer: an outgoing payment is final by
     * its deadline ({@code tpe.outgoing.deadline-offset} of the orchestrator), and its event is delivered as any
     * outbox event is.
     */
    public static Duration paymentFinal() {
        ServiceProfile orchestrator = ServiceProfile.systemtest(ServiceJar.ORCHESTRATOR);
        return orchestrator.duration("tpe.outgoing.deadline-offset").orElseThrow()
                .plus(eventDelivery(orchestrator));
    }

    /**
     * How long a pending action may be overdue before INV-11 calls it stuck: the orchestrator's
     * {@code tpe.runner.lease + tpe.runner.max-backoff}.
     */
    public static Duration actionOverdue() {
        ServiceProfile orchestrator = ServiceProfile.systemtest(ServiceJar.ORCHESTRATOR);
        return orchestrator.duration("tpe.runner.lease").orElseThrow()
                .plus(orchestrator.duration("tpe.runner.max-backoff").orElseThrow());
    }

    /**
     * How long past its {@code sendBy} a transmission may still be {@code PENDING} before INV-11 calls it stuck:
     * csm-gateway's {@code tpe.csm.transmit.lease} (a claimed transmission's last attempt) and its poll interval.
     */
    public static Duration transmissionOverdue() {
        ServiceProfile gateway = ServiceProfile.systemtest(ServiceJar.CSM_GATEWAY);
        return gateway.duration("tpe.csm.transmit.lease").orElseThrow()
                .plus(gateway.duration("tpe.csm.transmit.poll-interval").orElseThrow());
    }

    /**
     * How long after the start of a transmission the CSM may log its receipt: csm-gateway's
     * {@code tpe.csm.timeouts.connect}, within which the request has reached the simulator or failed. INV-5 counts a
     * {@code pacs.008} sent just before its {@code sendBy} as sent in time.
     */
    public static Duration transmissionInFlight() {
        return ServiceProfile.systemtest(ServiceJar.CSM_GATEWAY).duration("tpe.csm.timeouts.connect").orElseThrow();
    }

    /** Until a VoP reference has expired: vop-service's {@code tpe.vop.reference-validity} and the harness's side. */
    public static Duration referenceExpiry() {
        return ServiceProfile.systemtest(ServiceJar.VOP).duration("tpe.vop.reference-validity").orElseThrow()
                .plus(HARNESS);
    }

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
