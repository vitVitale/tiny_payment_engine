package com.tpe.riskservice.domain.rules;

/**
 * {@code AMOUNT_LIMIT} (specs/14-risk-service.md §6.1): hits when the amount is above {@code maxAmountMinor}
 * (default 50 000.00 EUR), in both directions.
 */
public final class AmountLimitRule implements Rule {

    public static final String ID = "AMOUNT_LIMIT";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public RuleResult evaluate(PaymentToAssess payment, RuleParameters parameters) {
        return payment.amount().minor() > parameters.longValue("maxAmountMinor")
                ? RuleResult.hit(1.0)
                : RuleResult.NO_HIT;
    }
}
