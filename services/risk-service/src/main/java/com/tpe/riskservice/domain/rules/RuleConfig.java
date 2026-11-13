package com.tpe.riskservice.domain.rules;

import java.util.Objects;

/** A row of the {@code rules} table (specs/14-risk-service.md §5): a rule's configuration. */
public record RuleConfig(
        String ruleId, boolean enabled, RuleDirection direction, RuleParameters parameters, RuleAction action) {

    public RuleConfig {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(action, "action");
    }

    boolean appliesTo(PaymentDirection payment) {
        return enabled && direction.covers(payment);
    }
}
