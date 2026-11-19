package com.tpe.systemtests.harness;

import com.tpe.messaging.outbox.OutboxRelay;
import java.time.Duration;
import java.util.List;

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
     * From a {@code 202} to the final state of a payment the CSM left without an answer, resolved by the first status
     * request: the deadline, the first {@code tpe.investigation.backoff} value (a lost request is asked again), and
     * the event's delivery.
     */
    public static Duration paymentFinalAfterInvestigation() {
        return paymentFinal().plus(investigationBackoff().getFirst());
    }

    /**
     * From an operator's {@code RETRY_INVESTIGATION} to the final state: the new status request goes out at once and
     * is asked again after the first back-off value if lost, then the event's delivery.
     */
    public static Duration statusRequestAnswered() {
        return investigationBackoff().getFirst()
                .plus(eventDelivery(ServiceProfile.systemtest(ServiceJar.ORCHESTRATOR)));
    }

    /**
     * From a {@code 202} to the {@code UNRESOLVED_INVESTIGATION} case of a payment the CSM never answers: the
     * deadline, {@code tpe.investigation.budget}, the last back-off (the first retry past the budget opens it), and
     * the harness's side.
     */
    public static Duration investigationUnresolved() {
        ServiceProfile orchestrator = ServiceProfile.systemtest(ServiceJar.ORCHESTRATOR);
        return orchestrator.duration("tpe.outgoing.deadline-offset").orElseThrow()
                .plus(orchestrator.duration("tpe.investigation.budget").orElseThrow())
                .plus(investigationBackoff().getLast())
                .plus(HARNESS);
    }

    /**
     * How long E2E-I-06 holds the orchestrator's produce back: csm-gateway's {@code tpe.csm.answer-deadline-offset} and
     * {@code tpe.csm.answer-transmit-window}, so the verdict reaches the gateway only after its deadline answer has
     * been decided and sent. A delay, not an upper bound: derived from the same profile.
     */
    public static Duration verdictHeldPastAnswer() {
        ServiceProfile gateway = ServiceProfile.systemtest(ServiceJar.CSM_GATEWAY);
        return gateway.duration("tpe.csm.answer-deadline-offset").orElseThrow()
                .plus(gateway.duration("tpe.csm.answer-transmit-window").orElseThrow());
    }

    /**
     * From an incoming {@code pacs.008} the CSM settles without confirming to its payment {@code SETTLED} (E2E-I-08):
     * our answer by csm-gateway's {@code tpe.csm.answer-deadline-offset}, the orchestrator's
     * {@code tpe.incoming.settlement-overdue} after it, the first {@code tpe.investigation.backoff} value (a lost
     * request is asked again), and the event's delivery.
     */
    public static Duration incomingSettledAfterInvestigation() {
        ServiceProfile orchestrator = ServiceProfile.systemtest(ServiceJar.ORCHESTRATOR);
        return ServiceProfile.systemtest(ServiceJar.CSM_GATEWAY).duration("tpe.csm.answer-deadline-offset")
                .orElseThrow()
                .plus(orchestrator.duration("tpe.incoming.settlement-overdue").orElseThrow())
                .plus(investigationBackoff().getFirst())
                .plus(eventDelivery(orchestrator));
    }

    /** The orchestrator's {@code tpe.investigation.backoff} in the {@code systemtest} profile. */
    private static List<Duration> investigationBackoff() {
        List<Duration> backoff = ServiceProfile.systemtest(ServiceJar.ORCHESTRATOR)
                .durations("tpe.investigation.backoff");
        if (backoff.isEmpty()) {
            throw new IllegalStateException("tpe.investigation.backoff is not set");
        }
        return backoff;
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
     * How long past its {@code answer_deadline} an incoming transfer may still be unanswered, or its answer
     * {@code PENDING}, before INV-11 calls it stuck: csm-gateway's {@code tpe.csm.answer-transmit-window} (the answer is
     * sent until then) and {@link #transmissionOverdue()}.
     */
    public static Duration answerOverdue() {
        return ServiceProfile.systemtest(ServiceJar.CSM_GATEWAY).duration("tpe.csm.answer-transmit-window")
                .orElseThrow().plus(transmissionOverdue());
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

    /**
     * From a {@code pacs.002} taken by csm-gateway to the orchestrator having handled its event: the gateway's outbox
     * delivers it as any outbox event is (E2E-O-14's late contradicting report).
     */
    public static Duration csmEventHandled() {
        return eventDelivery(ServiceProfile.systemtest(ServiceJar.CSM_GATEWAY));
    }

    /**
     * From a row in payment-orchestrator's outbox to its record in Kafka, the relay working as usual: CH-03's backlog
     * after Kafka is reachable again.
     */
    public static Duration orchestratorEventDelivery() {
        return eventDelivery(ServiceProfile.systemtest(ServiceJar.ORCHESTRATOR));
    }

    /** csm-gateway's {@code tpe.csm.transmit.lease}: a claimed transmission is not taken again before it (CH-02). */
    public static Duration transmissionLease() {
        return ServiceProfile.systemtest(ServiceJar.CSM_GATEWAY).duration("tpe.csm.transmit.lease").orElseThrow();
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
