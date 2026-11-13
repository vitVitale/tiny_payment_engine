package com.tpe.riskservice.domain.rules;

import com.tpe.paymentdomain.ReasonCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Runs the rules on a payment and aggregates the decision (specs/14-risk-service.md §6.1): a rule runs if its
 * configuration exists, is enabled and covers the payment's direction. Any {@code DENY} hit → {@code DENY}, with
 * {@code RR04} if a sanctions rule's {@code DENY} hit, else {@code FR01}; else any {@code REVIEW} hit →
 * {@code REVIEW} ({@code FR01}); else {@code ALLOW}. A configuration without a rule (one a later release implements)
 * is ignored.
 */
public final class RuleEngine {

    private final List<Rule> rules;

    public RuleEngine(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public Verdict decide(PaymentToAssess payment, RuleSet configs) {
        List<RuleHit> hits = new ArrayList<>();
        boolean deny = false;
        boolean sanctionsDeny = false;
        boolean review = false;
        for (Rule rule : rules) {
            Optional<RuleConfig> config = configs.get(rule.id()).filter(c -> c.appliesTo(payment.direction()));
            if (config.isEmpty()) {
                continue;
            }
            RuleResult result = rule.evaluate(payment, config.get().parameters());
            if (!result.hit()) {
                continue;
            }
            hits.add(new RuleHit(rule.id(), result.score()));
            if (config.get().action() == RuleAction.DENY) {
                deny = true;
                sanctionsDeny |= rule.sanctions();
            } else {
                review = true;
            }
        }
        if (deny) {
            return new Verdict(Decision.DENY, sanctionsDeny ? ReasonCode.RR04 : ReasonCode.FR01, hits);
        }
        return review ? new Verdict(Decision.REVIEW, ReasonCode.FR01, hits) : new Verdict(Decision.ALLOW, null, hits);
    }
}
