package com.tpe.riskservice.domain.rules;

import java.time.Duration;
import java.util.Optional;

/**
 * A fraud or sanctions rule (specs/14-risk-service.md §6.1): the logic is code, its parameters, action and direction
 * come from the {@code rules} table ({@link RuleConfig}). Evaluates in memory, without I/O: the payer's history it
 * needs is read beforehand into a {@link RiskContext}.
 */
public interface Rule {

    /** The {@code rules.rule_id} of its configuration. */
    String id();

    /** A sanctions rule's {@code DENY} gives {@code RR04} instead of {@code FR01}. */
    default boolean sanctions() {
        return false;
    }

    /**
     * How far back the rule reads the payer's history in the {@link RiskContext}: {@link Duration#ZERO} if it reads
     * no window, empty (the default) if it reads no history at all.
     */
    default Optional<Duration> lookback(RuleParameters parameters) {
        return Optional.empty();
    }

    /** {@code context} holds at least the {@link #lookback} of this rule. */
    RuleResult evaluate(PaymentToAssess payment, RuleParameters parameters, RiskContext context);
}
