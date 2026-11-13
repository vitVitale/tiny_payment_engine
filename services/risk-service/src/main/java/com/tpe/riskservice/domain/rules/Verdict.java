package com.tpe.riskservice.domain.rules;

import com.tpe.paymentdomain.ReasonCode;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of the rules for one payment (specs/14-risk-service.md §6.1).
 *
 * @param reasonCode {@code FR01} or {@code RR04}; {@code null} for {@code ALLOW}
 * @param ruleHits every rule that hit, in rule order, whatever its action
 */
public record Verdict(Decision decision, ReasonCode reasonCode, List<RuleHit> ruleHits) {

    public Verdict {
        Objects.requireNonNull(decision, "decision");
        ruleHits = List.copyOf(ruleHits);
        if ((decision == Decision.ALLOW) != (reasonCode == null)) {
            throw new IllegalArgumentException("a reason code is given for DENY and REVIEW only");
        }
    }
}
