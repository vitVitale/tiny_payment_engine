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
 * {@code MULE_NEW_ACCOUNT} (specs/14-risk-service.md §6.1): an incoming payment to a creditor account opened less than
 * {@code maxAccountAge} ago that would make more than {@code maxIncomingCount} allowed incoming payments within
 * {@code window}. A creditor account the projection does not know never hits.
 */
class MuleNewAccountRuleTest {

    private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");
    private static final PaymentToAssess PAYMENT = payment(PaymentDirection.INCOMING, Money.parse("10.00"));

    private final MuleNewAccountRule rule = new MuleNewAccountRule();
    /** 7 days, 10 in 24 h: the seeded defaults. */
    private final RuleParameters defaults = new RuleParameters(
            Map.of("maxAccountAge", "P7D", "maxIncomingCount", 10, "window", "PT24H"));

    @Test
    void theEleventhIncomingPaymentToANewAccountHits() {
        assertThat(rule.evaluate(PAYMENT, defaults, history(10, Duration.ofDays(6)))).isEqualTo(RuleResult.hit(1.0));
    }

    @Test
    void theTenthIncomingPaymentToANewAccountIsAllowed() {
        assertThat(rule.evaluate(PAYMENT, defaults, history(9, Duration.ofDays(6)))).isEqualTo(RuleResult.NO_HIT);
    }

    @Test
    void anAccountOpenedSevenDaysAgoIsNoLongerNew() {
        assertThat(rule.evaluate(PAYMENT, defaults, history(10, Duration.ofDays(7)))).isEqualTo(RuleResult.NO_HIT);
    }

    @Test
    void anUnknownCreditorAccountNeverHits() {
        RiskContext unknown = RiskContext.loaded(NOW, Duration.ofHours(24), payments(10), false, null);

        assertThat(rule.evaluate(PAYMENT, defaults, unknown)).isEqualTo(RuleResult.NO_HIT);
    }

    @Test
    void looksBackOverItsWindow() {
        assertThat(rule.lookback(defaults)).isEqualTo(Optional.of(Duration.ofHours(24)));
        assertThat(rule.id()).isEqualTo("MULE_NEW_ACCOUNT");
    }

    /** {@code count} allowed incoming payments in the last hour, to an account opened {@code age} ago. */
    private static RiskContext history(int count, Duration age) {
        return RiskContext.loaded(NOW, Duration.ofHours(24), payments(count), false, NOW.minus(age));
    }

    private static List<PastPayment> payments(int count) {
        List<PastPayment> payments = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            payments.add(new PastPayment(NOW.minus(Duration.ofMinutes(i + 1)), 10_00));
        }
        return payments;
    }
}
