package com.tpe.riskservice.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.PartyName;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** {@code AMOUNT_LIMIT} (specs/14-risk-service.md §6.1): an amount above {@code maxAmountMinor}, either direction. */
class AmountLimitRuleTest {

    private final AmountLimitRule rule = new AmountLimitRule();
    /** 50 000.00 EUR, the seeded default. */
    private final RuleParameters limit = new RuleParameters(Map.of("maxAmountMinor", 5_000_000L));

    @ParameterizedTest
    @EnumSource(PaymentDirection.class)
    void anAmountUpToTheLimitIsAllowed(PaymentDirection direction) {
        assertThat(rule.evaluate(payment(direction, Money.parse("50000.00")), limit)).isEqualTo(RuleResult.NO_HIT);
        assertThat(rule.evaluate(payment(direction, Money.parse("0.01")), limit)).isEqualTo(RuleResult.NO_HIT);
    }

    @ParameterizedTest
    @EnumSource(PaymentDirection.class)
    void anAmountAboveTheLimitHits(PaymentDirection direction) {
        assertThat(rule.evaluate(payment(direction, Money.parse("50000.01")), limit)).isEqualTo(RuleResult.hit(1.0));
    }

    @ParameterizedTest
    @EnumSource(PaymentDirection.class)
    void theLimitComesFromTheParameters(PaymentDirection direction) {
        var lower = new RuleParameters(Map.of("maxAmountMinor", 100_00));

        assertThat(rule.evaluate(payment(direction, Money.parse("100.01")), lower)).isEqualTo(RuleResult.hit(1.0));
    }

    static PaymentToAssess payment(PaymentDirection direction, Money amount) {
        return new PaymentToAssess(direction, amount, new PartyName("Anna Schmidt"),
                new Iban("DE36100100100000000001"), new PartyName("Max Mustermann"),
                new Iban("DE07200200200123456789"),
                direction == PaymentDirection.OUTGOING ? PayeeVerification.MATCH : null);
    }
}
