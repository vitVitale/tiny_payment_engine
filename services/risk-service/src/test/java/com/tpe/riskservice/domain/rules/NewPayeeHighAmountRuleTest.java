package com.tpe.riskservice.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.PartyName;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@code NEW_PAYEE_HIGH_AMOUNT} (specs/14-risk-service.md §6.1, UC-04 2b): the debtor's first allowed payment to this
 * creditor IBAN, above {@code minAmountMinor}, to a payee the payer did not see as a VoP {@code MATCH}.
 */
class NewPayeeHighAmountRuleTest {

    private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");

    private final NewPayeeHighAmountRule rule = new NewPayeeHighAmountRule();
    /** 5 000.00 EUR, the seeded default. */
    private final RuleParameters defaults = new RuleParameters(Map.of("minAmountMinor", 500_000));

    @Test
    void aFirstPaymentAboveTheAmountToAMatchedPayeeIsAllowed() {
        assertThat(rule.evaluate(outgoing("6000.00", PayeeVerification.MATCH), defaults, paidBefore(false)))
                .isEqualTo(RuleResult.NO_HIT);
    }

    @ParameterizedTest
    @EnumSource(value = PayeeVerification.class, names = "MATCH", mode = EnumSource.Mode.EXCLUDE)
    void aFirstPaymentAboveTheAmountToAPayeeNotMatchedHits(PayeeVerification vop) {
        assertThat(rule.evaluate(outgoing("6000.00", vop), defaults, paidBefore(false)))
                .isEqualTo(RuleResult.hit(1.0));
    }

    @Test
    void anAmountUpToTheThresholdIsAllowed() {
        assertThat(rule.evaluate(outgoing("5000.00", PayeeVerification.NO_MATCH), defaults, paidBefore(false)))
                .isEqualTo(RuleResult.NO_HIT);
    }

    @Test
    void aPayeePaidBeforeIsAllowed() {
        assertThat(rule.evaluate(outgoing("6000.00", PayeeVerification.NO_MATCH), defaults, paidBefore(true)))
                .isEqualTo(RuleResult.NO_HIT);
    }

    @Test
    void anIncomingPaymentCarriesNoVopResultAndNeverHits() {
        var incoming = AmountLimitRuleTest.payment(PaymentDirection.INCOMING, Money.parse("6000.00"));

        assertThat(rule.evaluate(incoming, defaults, paidBefore(false))).isEqualTo(RuleResult.NO_HIT);
    }

    @Test
    void needsTheHistoryButNoWindow() {
        assertThat(rule.lookback(defaults)).isEqualTo(Optional.of(Duration.ZERO));
        assertThat(rule.id()).isEqualTo("NEW_PAYEE_HIGH_AMOUNT");
    }

    private static PaymentToAssess outgoing(String amount, PayeeVerification vop) {
        return new PaymentToAssess(PaymentDirection.OUTGOING, Money.parse(amount), new PartyName("Anna Schmidt"),
                new Iban("DE36100100100000000001"), new PartyName("Max Mustermann"),
                new Iban("DE07200200200123456789"), vop);
    }

    private static RiskContext paidBefore(boolean paid) {
        return RiskContext.loaded(NOW, Duration.ZERO, List.of(), paid, null);
    }
}
