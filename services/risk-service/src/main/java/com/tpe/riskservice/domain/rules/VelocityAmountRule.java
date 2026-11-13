package com.tpe.riskservice.domain.rules;

import java.time.Duration;
import java.util.Optional;

/**
 * {@code VELOCITY_AMOUNT_24H} (specs/14-risk-service.md §6.1): hits when this payment would take the allowed payments
 * of the debtor IBAN within {@code window} (default 24 h) above {@code maxAmountMinor} (default 100 000.00 EUR).
 */
public final class VelocityAmountRule implements Rule {

    public static final String ID = "VELOCITY_AMOUNT_24H";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Optional<Duration> lookback(RuleParameters parameters) {
        return Optional.of(parameters.duration("window"));
    }

    @Override
    public RuleResult evaluate(PaymentToAssess payment, RuleParameters parameters, RiskContext context) {
        long total = Math.addExact(context.amountMinor(parameters.duration("window")), payment.amount().minor());
        return total > parameters.longValue("maxAmountMinor") ? RuleResult.hit(1.0) : RuleResult.NO_HIT;
    }
}
