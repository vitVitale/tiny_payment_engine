package com.tpe.riskservice.domain.rules;

import java.time.Duration;
import java.util.Optional;

/**
 * {@code MULE_NEW_ACCOUNT} (specs/14-risk-service.md §6.1): hits on an incoming payment to a creditor account opened
 * less than {@code maxAccountAge} (default 7 days) ago when it would make more than {@code maxIncomingCount} (default
 * 10) allowed incoming payments to that account within {@code window} (default 24 h). A creditor account the holder
 * projection does not know never hits: ledger-service refuses the payment instead.
 */
public final class MuleNewAccountRule implements Rule {

    public static final String ID = "MULE_NEW_ACCOUNT";

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
        boolean newAccount = context.accountAge()
                .filter(age -> age.compareTo(parameters.duration("maxAccountAge")) < 0)
                .isPresent();
        boolean hit = newAccount
                && context.count(parameters.duration("window")) + 1 > parameters.longValue("maxIncomingCount");
        return hit ? RuleResult.hit(1.0) : RuleResult.NO_HIT;
    }
}
