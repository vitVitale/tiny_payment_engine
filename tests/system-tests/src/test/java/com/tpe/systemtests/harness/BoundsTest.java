package com.tpe.systemtests.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BoundsTest {

    @Test
    void theEventDeliveryBoundFollowsTheRelaySettingsOfTheLastProfileThatSetsThem() {
        var defaults = Bounds.eventDelivery(new ServiceProfile(List.of(Map.of())));
        var profile = new ServiceProfile(List.of(
                Map.of("tpe", Map.of("messaging", Map.of("outbox", Map.of("takeover-retry", "2s")))),
                Map.of("tpe", Map.of("messaging", Map.of("outbox", Map.of("takeover-retry", "500ms",
                        "visibility-window", "PT0.12S"))))));

        // defaults: takeover 1 s, window 20 ms; the overrides: 500 ms and 120 ms
        assertThat(Bounds.eventDelivery(profile)).isEqualTo(defaults.minusMillis(500).plusMillis(100));
    }

    @Test
    void theLedgerProfileIsReadFromTheServicesSources() {
        assertThat(ServiceProfile.ledger().duration("tpe.ledger.snapshot.interval")).contains(Duration.ofSeconds(5));
        assertThat(ServiceProfile.ledger().duration("tpe.ledger.idempotency.retention")).contains(Duration.ofDays(30));
    }

    /** 20 §2.1: a payment is final by the orchestrator's deadline of the systemtest profile, 6 s, not 20 s. */
    @Test
    void aPaymentIsFinalByTheOrchestratorsSystemTestDeadlinePlusEventDelivery() {
        ServiceProfile orchestrator = ServiceProfile.systemtest(ServiceJar.ORCHESTRATOR);

        assertThat(orchestrator.duration("tpe.outgoing.deadline-offset")).contains(Duration.ofSeconds(6));
        assertThat(Bounds.paymentFinal()).isEqualTo(Duration.ofSeconds(6).plus(Bounds.eventDelivery(orchestrator)));
    }

    /** E2E-O-17: a reference has expired once vop-service's systemtest validity (5 s, 20 §2.1) has passed. */
    @Test
    void aReferenceExpiresAfterVopServicesSystemTestValidity() {
        assertThat(Bounds.referenceExpiry()).isEqualTo(Duration.ofSeconds(5).plusSeconds(2));
    }

    /** E2E-O-09, -10, -20: answered by the first status request, asked again after 500 ms (20 §2.1) if lost. */
    @Test
    void aPaymentLeftWithoutAnAnswerIsFinalAfterTheFirstStatusRequest() {
        assertThat(Bounds.paymentFinalAfterInvestigation())
                .isEqualTo(Bounds.paymentFinal().plus(Duration.ofMillis(500)));
    }

    /** E2E-O-11: deadline 6 s, budget 10 s, then the next retry after the last back-off value, 1 s (20 §2.1). */
    @Test
    void anInvestigationIsUnresolvedAfterTheDeadlineTheBudgetAndTheLastBackoff() {
        assertThat(Bounds.investigationUnresolved()).isEqualTo(Duration.ofSeconds(6 + 10 + 1 + 2));
    }

    /** E2E-O-11's retry: the request at once, asked again after 500 ms, then the event's delivery. */
    @Test
    void aRetriedInvestigationIsAnsweredWithinTheFirstBackoffAndEventDelivery() {
        ServiceProfile orchestrator = ServiceProfile.systemtest(ServiceJar.ORCHESTRATOR);

        assertThat(Bounds.statusRequestAnswered())
                .isEqualTo(Duration.ofMillis(500).plus(Bounds.eventDelivery(orchestrator)));
    }

    @Test
    void anActionIsOverdueAfterTheOrchestratorsLeaseAndLongestBackoff() {
        assertThat(Bounds.actionOverdue()).isEqualTo(Duration.ofSeconds(35));
    }

    /** 20 §2.1: the systemtest profile keeps the order of the production values, so the same races occur. */
    @Test
    void theOrchestratorsSystemTestTimingsKeepTheProductionOrder() {
        ServiceProfile orchestrator = ServiceProfile.systemtest(ServiceJar.ORCHESTRATOR);
        Duration margin = orchestrator.duration("tpe.outgoing.hold-give-up-margin").orElseThrow();
        Duration sendBy = orchestrator.duration("tpe.outgoing.send-by-offset").orElseThrow();
        Duration deadline = orchestrator.duration("tpe.outgoing.deadline-offset").orElseThrow();

        assertThat(margin).isLessThan(sendBy);
        assertThat(sendBy).isLessThan(deadline);
    }
}
