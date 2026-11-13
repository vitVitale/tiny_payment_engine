package com.tpe.riskservice.domain.rules;

import java.time.Duration;
import java.util.Optional;

/**
 * {@code VELOCITY_COUNT_1H} (specs/14-risk-service.md §6.1): hits when this payment would make more than
 * {@code maxCount} (default 20) allowed payments of the debtor IBAN within {@code window} (default 1 h).
 */
public final class VelocityCountRule implements Rule {

    public static final String ID = "VELOCITY_COUNT_1H";

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
        return context.count(parameters.duration("window")) + 1 > parameters.longValue("maxCount")
                ? RuleResult.hit(1.0)
                : RuleResult.NO_HIT;
    }
}
