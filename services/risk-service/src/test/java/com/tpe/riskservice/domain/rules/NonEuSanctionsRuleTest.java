package com.tpe.riskservice.domain.rules;

import static com.tpe.riskservice.domain.rules.AmountLimitRuleTest.payment;
import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.paymentdomain.Money;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@code NON_EU_SANCTIONS} (specs/14-risk-service.md §6.1) is wired as the sanctions rule; until the sanctions track
 * imports non-EU lists there is nothing to match, so it never hits.
 */
class NonEuSanctionsRuleTest {

    private final NonEuSanctionsRule rule = new NonEuSanctionsRule();

    @ParameterizedTest
    @EnumSource(PaymentDirection.class)
    void neverHitsWithoutImportedLists(PaymentDirection direction) {
        assertThat(rule.evaluate(payment(direction, Money.parse("10.00")),
                new RuleParameters(Map.of("threshold", 0.97)), RiskContext.NOT_LOADED)).isEqualTo(RuleResult.NO_HIT);
    }

    @Test
    void isTheSanctionsRule() {
        assertThat(rule.id()).isEqualTo("NON_EU_SANCTIONS");
        assertThat(rule.sanctions()).isTrue();
    }
}
