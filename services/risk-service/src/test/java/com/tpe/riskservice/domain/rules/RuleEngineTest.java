package com.tpe.riskservice.domain.rules;

import static com.tpe.riskservice.domain.rules.AmountLimitRuleTest.payment;
import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.ReasonCode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The decision of specs/14-risk-service.md §6.1, with test rules: any {@code DENY} hit → {@code DENY} ({@code RR04}
 * if a sanctions rule hit, else {@code FR01}); else any {@code REVIEW} → {@code REVIEW} ({@code FR01}); else
 * {@code ALLOW}. A rule runs only if its parameters are configured, enabled and for the payment's direction.
 */
class RuleEngineTest {

    private static final PaymentToAssess OUTGOING = payment(PaymentDirection.OUTGOING, Money.parse("10.00"));
    private static final PaymentToAssess INCOMING = payment(PaymentDirection.INCOMING, Money.parse("10.00"));

    private final RuleEngine engine = new RuleEngine(List.of(
            fixed("FRAUD_A", false, RuleResult.hit(0.5)), fixed("FRAUD_B", false, RuleResult.hit(0.5)),
            fixed("SANCTIONS", true, RuleResult.hit(0.98)), fixed("QUIET", false, RuleResult.NO_HIT)));

    @Test
    void noHitIsAllowWithoutAReason() {
        Verdict verdict = decide(OUTGOING, rules(config("QUIET", RuleAction.DENY)));

        assertThat(verdict).isEqualTo(new Verdict(Decision.ALLOW, null, List.of()));
    }

    @Test
    void aReviewHitIsReviewWithFr01() {
        Verdict verdict = decide(OUTGOING, rules(config("FRAUD_A", RuleAction.REVIEW)));

        assertThat(verdict).isEqualTo(
                new Verdict(Decision.REVIEW, ReasonCode.FR01, List.of(new RuleHit("FRAUD_A", 0.5))));
    }

    @Test
    void denyBeatsReview() {
        Verdict verdict = decide(OUTGOING,
                rules(config("FRAUD_A", RuleAction.REVIEW), config("FRAUD_B", RuleAction.DENY)));

        assertThat(verdict.decision()).isEqualTo(Decision.DENY);
        assertThat(verdict.reasonCode()).isEqualTo(ReasonCode.FR01);
        assertThat(verdict.ruleHits()).extracting(RuleHit::ruleId).containsExactly("FRAUD_A", "FRAUD_B");
    }

    @Test
    void aSanctionsDenyGivesRr04OverFr01() {
        Verdict verdict = decide(OUTGOING, rules(config("FRAUD_B", RuleAction.DENY),
                config("SANCTIONS", RuleAction.DENY), config("FRAUD_A", RuleAction.REVIEW)));

        assertThat(verdict.decision()).isEqualTo(Decision.DENY);
        assertThat(verdict.reasonCode()).isEqualTo(ReasonCode.RR04);
        assertThat(verdict.ruleHits()).contains(new RuleHit("SANCTIONS", 0.98));
    }

    @Test
    void aSanctionsRuleConfiguredToReviewIsAReviewWithFr01() {
        Verdict verdict = decide(OUTGOING, rules(config("SANCTIONS", RuleAction.REVIEW)));

        assertThat(verdict.decision()).isEqualTo(Decision.REVIEW);
        assertThat(verdict.reasonCode()).isEqualTo(ReasonCode.FR01);
    }

    @Test
    void aRuleRunsOnlyWhenConfiguredEnabledAndForTheDirection() {
        var disabled = new RuleConfig("FRAUD_B", false, RuleDirection.BOTH, RuleParameters.NONE, RuleAction.DENY);
        var outgoingOnly = new RuleConfig("FRAUD_A", true, RuleDirection.OUTGOING, RuleParameters.NONE,
                RuleAction.DENY);

        assertThat(decide(OUTGOING, rules(disabled)).decision()).isEqualTo(Decision.ALLOW);
        // no configuration at all: the rule does not run
        assertThat(decide(OUTGOING, rules()).decision()).isEqualTo(Decision.ALLOW);
        assertThat(decide(OUTGOING, rules(outgoingOnly)).decision()).isEqualTo(Decision.DENY);
        assertThat(decide(INCOMING, rules(outgoingOnly)).decision()).isEqualTo(Decision.ALLOW);
    }

    @Test
    void aConfigurationWithoutARuleIsIgnored() {
        assertThat(decide(OUTGOING, rules(config("MULE_NEW_ACCOUNT", RuleAction.DENY))).decision())
                .isEqualTo(Decision.ALLOW);
    }

    @Test
    void eachRuleGetsItsOwnParameters() {
        List<RuleParameters> seen = new ArrayList<>();
        Rule spy = new Rule() {
            @Override
            public String id() {
                return "SPY";
            }

            @Override
            public RuleResult evaluate(PaymentToAssess payment, RuleParameters parameters, RiskContext context) {
                seen.add(parameters);
                return RuleResult.NO_HIT;
            }
        };
        var parameters = new RuleParameters(Map.of("threshold", 3));

        new RuleEngine(List.of(spy)).decide(OUTGOING,
                rules(new RuleConfig("SPY", true, RuleDirection.BOTH, parameters, RuleAction.DENY)),
                RiskContext.NOT_LOADED);

        assertThat(seen).containsExactly(parameters);
    }

    @Test
    void theRulesGetTheContext() {
        RiskContext context = RiskContext.loaded(Instant.parse("2026-11-12T08:00:00Z"), Duration.ZERO, List.of(),
                true, null);
        Rule paidBefore = new Rule() {
            @Override
            public String id() {
                return "PAID_BEFORE";
            }

            @Override
            public RuleResult evaluate(PaymentToAssess payment, RuleParameters parameters, RiskContext context) {
                return context.payeePaidBefore() ? RuleResult.hit(1.0) : RuleResult.NO_HIT;
            }
        };

        Verdict verdict = new RuleEngine(List.of(paidBefore))
                .decide(OUTGOING, rules(config("PAID_BEFORE", RuleAction.DENY)), context);

        assertThat(verdict.decision()).isEqualTo(Decision.DENY);
    }

    @Test
    void theLookBackIsTheLongestOfTheRulesThatRun() {
        var history = new RuleEngine(List.of(looking("HOUR", Duration.ofHours(1)), looking("DAY", Duration.ofDays(1)),
                looking("NOW", Duration.ZERO), fixed("QUIET", false, RuleResult.NO_HIT)));
        var dayDisabled = new RuleConfig("DAY", false, RuleDirection.BOTH, RuleParameters.NONE, RuleAction.DENY);
        var dayIncoming = new RuleConfig("DAY", true, RuleDirection.INCOMING, RuleParameters.NONE, RuleAction.DENY);

        assertThat(history.lookback(OUTGOING, rules(config("HOUR", RuleAction.DENY), config("DAY", RuleAction.DENY),
                config("NOW", RuleAction.REVIEW)))).contains(Duration.ofDays(1));
        assertThat(history.lookback(OUTGOING, rules(config("HOUR", RuleAction.DENY), dayDisabled)))
                .contains(Duration.ofHours(1));
        assertThat(history.lookback(OUTGOING, rules(config("NOW", RuleAction.DENY), dayIncoming)))
                .contains(Duration.ZERO);
    }

    @Test
    void noLookBackWhenNoRuleThatRunsNeedsTheHistory() {
        var history = new RuleEngine(List.of(looking("DAY", Duration.ofDays(1)), fixed("QUIET", false,
                RuleResult.NO_HIT)));
        var dayOutgoing = new RuleConfig("DAY", true, RuleDirection.OUTGOING, RuleParameters.NONE, RuleAction.DENY);

        assertThat(history.lookback(INCOMING, rules(dayOutgoing, config("QUIET", RuleAction.DENY)))).isEmpty();
        assertThat(history.lookback(OUTGOING, rules())).isEmpty();
    }

    private Verdict decide(PaymentToAssess payment, RuleSet configs) {
        return engine.decide(payment, configs, RiskContext.NOT_LOADED);
    }

    /** A rule that reads the payer's history {@code lookback} far back, and never hits. */
    private static Rule looking(String id, Duration lookback) {
        return new Rule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Optional<Duration> lookback(RuleParameters parameters) {
                return Optional.of(lookback);
            }

            @Override
            public RuleResult evaluate(PaymentToAssess payment, RuleParameters parameters, RiskContext context) {
                return RuleResult.NO_HIT;
            }
        };
    }

    private static RuleSet rules(RuleConfig... configs) {
        return RuleSet.of(List.of(configs));
    }

    private static RuleConfig config(String ruleId, RuleAction action) {
        return new RuleConfig(ruleId, true, RuleDirection.BOTH, RuleParameters.NONE, action);
    }

    /** A rule that always answers {@code result}. */
    private static Rule fixed(String id, boolean sanctions, RuleResult result) {
        return new Rule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public boolean sanctions() {
                return sanctions;
            }

            @Override
            public RuleResult evaluate(PaymentToAssess payment, RuleParameters parameters, RiskContext context) {
                return result;
            }
        };
    }
}
