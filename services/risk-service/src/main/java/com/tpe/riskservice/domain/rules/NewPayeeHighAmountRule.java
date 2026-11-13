package com.tpe.riskservice.domain.rules;

import java.time.Duration;
import java.util.Optional;

/**
 * {@code NEW_PAYEE_HIGH_AMOUNT} (specs/14-risk-service.md §6.1, UC-04 2b): hits on the debtor IBAN's first allowed
 * payment to this creditor IBAN when the amount is above {@code minAmountMinor} (default 5 000.00 EUR) and the payer
 * did not see the payee as a VoP {@code MATCH}. Only an outgoing payment carries the VoP result; an incoming one
 * never hits.
 */
public final class NewPayeeHighAmountRule implements Rule {

    public static final String ID = "NEW_PAYEE_HIGH_AMOUNT";

    @Override
    public String id() {
        return ID;
    }

    /** Reads whether the payee was paid before, no window. */
    @Override
    public Optional<Duration> lookback(RuleParameters parameters) {
        return Optional.of(Duration.ZERO);
    }

    @Override
    public RuleResult evaluate(PaymentToAssess payment, RuleParameters parameters, RiskContext context) {
        PayeeVerification vop = payment.payeeVerification();
        boolean hit = vop != null && vop != PayeeVerification.MATCH
                && payment.amount().minor() > parameters.longValue("minAmountMinor")
                && !context.payeePaidBefore();
        return hit ? RuleResult.hit(1.0) : RuleResult.NO_HIT;
    }
}
