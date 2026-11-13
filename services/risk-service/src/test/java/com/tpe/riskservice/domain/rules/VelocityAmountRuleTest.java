package com.tpe.riskservice.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.paymentdomain.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@code VELOCITY_AMOUNT_24H} (specs/14-risk-service.md §6.1): hits when this payment would take the allowed payments
 * of the debtor IBAN within {@code window} above {@code maxAmountMinor}.
 */
class VelocityAmountRuleTest {

    private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");

    private final VelocityAmountRule rule = new VelocityAmountRule();
    /** 100 000.00 EUR in 24 h, the seeded default. */
    private final RuleParameters defaults =
            new RuleParameters(Map.of("maxAmountMinor", 10_000_000L, "window", "PT24H"));

    @Test
    void reachingTheLimitIsAllowed() {
        assertThat(rule.evaluate(payment("40000.00"), defaults, history(Duration.ofHours(23), 6_000_000)))
                .isEqualTo(RuleResult.NO_HIT);
    }

    @Test
    void goingAboveTheLimitHits() {
        assertThat(rule.evaluate(payment("40000.01"), defaults, history(Duration.ofHours(23), 6_000_000)))
                .isEqualTo(RuleResult.hit(1.0));
    }

    @Test
    void paymentsOlderThanTheWindowDoNotCount() {
        assertThat(rule.evaluate(payment("40000.01"), defaults, history(Duration.ofHours(24), 6_000_000)))
                .isEqualTo(RuleResult.NO_HIT);
    }

    @Test
    void looksBackOverItsWindow() {
        assertThat(rule.lookback(defaults)).isEqualTo(Optional.of(Duration.ofHours(24)));
        assertThat(rule.id()).isEqualTo("VELOCITY_AMOUNT_24H");
    }

    private static PaymentToAssess payment(String amount) {
        return AmountLimitRuleTest.payment(PaymentDirection.OUTGOING, Money.parse(amount));
    }

    private static RiskContext history(Duration age, long amountMinor) {
        return RiskContext.loaded(NOW, Duration.ofHours(24), List.of(new PastPayment(NOW.minus(age), amountMinor)),
                true, null);
    }
}
