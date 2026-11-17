package com.tpe.paymentorchestrator.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code tpe.investigation.backoff} and {@code .budget} (specs/11-payment-orchestrator.md §6.4, §7, UC-05 4a, 4b):
 * when the next status request of an investigation is due, and when the investigation gives up.
 */
class InvestigationPolicyTest {

    private static final InvestigationPolicy DEFAULTS = new InvestigationPolicy(List.of(Duration.ofSeconds(5),
            Duration.ofSeconds(15), Duration.ofSeconds(60), Duration.ofSeconds(300)), Duration.ofHours(1));
    private static final Instant STARTED = Instant.parse("2026-11-17T10:00:20Z");

    @Test
    void theRequestsOfAnInvestigationFollowTheBackoffAndItsLastValueRepeats() {
        assertThat(DEFAULTS.delayAfter(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(DEFAULTS.delayAfter(2)).isEqualTo(Duration.ofSeconds(15));
        assertThat(DEFAULTS.delayAfter(3)).isEqualTo(Duration.ofSeconds(60));
        assertThat(DEFAULTS.delayAfter(4)).isEqualTo(Duration.ofMinutes(5));
        assertThat(DEFAULTS.delayAfter(5)).isEqualTo(Duration.ofMinutes(5));
        assertThat(DEFAULTS.delayAfter(40)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void anInvestigationGivesUpOnlyOnceItHasRunLongerThanTheBudget() {
        assertThat(DEFAULTS.exhausted(STARTED, STARTED.plusSeconds(3599))).isFalse();
        assertThat(DEFAULTS.exhausted(STARTED, STARTED.plus(Duration.ofHours(1)))).isFalse();
        assertThat(DEFAULTS.exhausted(STARTED, STARTED.plus(Duration.ofHours(1)).plusMillis(1))).isTrue();
    }

    @Test
    void thereIsNoDelayBeforeTheFirstRequest() {
        assertThatThrownBy(() -> DEFAULTS.delayAfter(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPolicyNeedsPositiveDelaysAndBudget() {
        Duration budget = Duration.ofHours(1);
        assertThatThrownBy(() -> new InvestigationPolicy(List.of(), budget))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvestigationPolicy(List.of(Duration.ofSeconds(5), Duration.ZERO), budget))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvestigationPolicy(List.of(Duration.ofSeconds(5)), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
