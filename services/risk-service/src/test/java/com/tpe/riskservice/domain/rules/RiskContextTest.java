package com.tpe.riskservice.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The payer's history the velocity and new-payee rules read (specs/14-risk-service.md §6.1): the {@code ALLOW}
 * outgoing payments of the debtor IBAN within the loaded look-back, and whether the creditor IBAN was paid before.
 */
class RiskContextTest {

    private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");

    private final RiskContext context = RiskContext.loaded(NOW, Duration.ofHours(24), List.of(
            new PastPayment(NOW.minus(Duration.ofHours(23)), 300_00),
            new PastPayment(NOW.minus(Duration.ofHours(1)), 200_00),
            new PastPayment(NOW.minus(Duration.ofMinutes(59)), 100_00),
            new PastPayment(NOW, 50_00)), true);

    @Test
    void countsAndSumsThePaymentsWithinAWindowEndingNow() {
        assertThat(context.count(Duration.ofHours(1))).isEqualTo(2);
        assertThat(context.amountMinor(Duration.ofHours(1))).isEqualTo(150_00);
        assertThat(context.count(Duration.ofHours(24))).isEqualTo(4);
        assertThat(context.amountMinor(Duration.ofHours(24))).isEqualTo(650_00);
        assertThat(context.payeePaidBefore()).isTrue();
    }

    @Test
    void aWindowBeyondTheLoadedLookBackIsAnError() {
        assertThatIllegalStateException().isThrownBy(() -> context.count(Duration.ofHours(25)));
        assertThatIllegalStateException().isThrownBy(() -> context.amountMinor(Duration.ofHours(25)));
    }

    @Test
    void nothingIsReadableWhenNoHistoryWasLoaded() {
        assertThatIllegalStateException().isThrownBy(() -> RiskContext.NOT_LOADED.count(Duration.ZERO));
        assertThatIllegalStateException().isThrownBy(RiskContext.NOT_LOADED::payeePaidBefore);
    }
}
