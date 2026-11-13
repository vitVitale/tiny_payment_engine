package com.tpe.riskservice.domain.rules;

/**
 * A fraud or sanctions rule (specs/14-risk-service.md §6.1): the logic is code, its parameters, action and direction
 * come from the {@code rules} table ({@link RuleConfig}). Evaluates in memory, without I/O.
 */
public interface Rule {

    /** The {@code rules.rule_id} of its configuration. */
    String id();

    /** A sanctions rule's {@code DENY} gives {@code RR04} instead of {@code FR01}. */
    default boolean sanctions() {
        return false;
    }

    RuleResult evaluate(PaymentToAssess payment, RuleParameters parameters);
}
