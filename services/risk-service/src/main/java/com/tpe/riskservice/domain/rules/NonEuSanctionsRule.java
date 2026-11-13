package com.tpe.riskservice.domain.rules;

/**
 * {@code NON_EU_SANCTIONS} (specs/14-risk-service.md §6.1): the counterparty name against the current non-EU lists,
 * the only per-payment source of {@code RR04}. Until the sanctions track imports lists (specs/30-delivery-plan.md,
 * M2) there is nothing to match it against, so it never hits; it is wired now so that the decision already knows it
 * as the sanctions rule.
 */
public final class NonEuSanctionsRule implements Rule {

    public static final String ID = "NON_EU_SANCTIONS";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean sanctions() {
        return true;
    }

    @Override
    public RuleResult evaluate(PaymentToAssess payment, RuleParameters parameters) {
        return RuleResult.NO_HIT;
    }
}
