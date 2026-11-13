package com.tpe.riskservice.domain.rules;

import static com.tpe.riskservice.domain.rules.AmountLimitRuleTest.payment;
import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.paymentdomain.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@code VELOCITY_COUNT_1H} (specs/14-risk-service.md §6.1): hits when this payment would make more than
 * {@code maxCount} allowed payments of the debtor IBAN within {@code window}.
 */
class VelocityCountRuleTest {

    private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");
    private static final PaymentToAssess PAYMENT = payment(PaymentDirection.OUTGOING, Money.parse("10.00"));

    private final VelocityCountRule rule = new VelocityCountRule();
    /** 20 in 1 h, the seeded default. */
    private final RuleParameters defaults = new RuleParameters(Map.of("maxCount", 20, "window", "PT1H"));

    @Test
    void theTwentiethPaymentWithinTheWindowIsAllowed() {
        assertThat(rule.evaluate(PAYMENT, defaults, history(19, Duration.ofMinutes(59))))
                .isEqualTo(RuleResult.NO_HIT);
    }

    @Test
    void theTwentyFirstPaymentWithinTheWindowHits() {
        assertThat(rule.evaluate(PAYMENT, defaults, history(20, Duration.ofMinutes(59))))
                .isEqualTo(RuleResult.hit(1.0));
    }

    @Test
    void paymentsOlderThanTheWindowDoNotCount() {
        assertThat(rule.evaluate(PAYMENT, defaults, history(20, Duration.ofHours(1))))
                .isEqualTo(RuleResult.NO_HIT);
    }

    @Test
    void looksBackOverItsWindow() {
        assertThat(rule.lookback(defaults)).isEqualTo(Optional.of(Duration.ofHours(1)));
        assertThat(rule.id()).isEqualTo("VELOCITY_COUNT_1H");
    }

    /** {@code count} allowed payments, each {@code age} old. */
    private static RiskContext history(int count, Duration age) {
        List<PastPayment> payments = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            payments.add(new PastPayment(NOW.minus(age), 10_00));
        }
        return RiskContext.loaded(NOW, Duration.ofHours(1), payments, true, null);
    }
}
