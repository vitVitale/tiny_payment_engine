package com.tpe.riskservice.application.assessment;

/**
 * No rule parameters have been loaded yet: {@code 503 RULES_UNAVAILABLE}, and the orchestrator rejects the payment
 * (fail closed, specs/14-risk-service.md §8).
 */
public final class RulesNotLoadedException extends RuntimeException {

    public RulesNotLoadedException() {
        super("the rule parameters have not been loaded yet");
    }
}
