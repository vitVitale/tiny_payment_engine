package com.tpe.riskservice.domain.rules;

/** The payments a rule applies to ({@code rules.direction}). */
public enum RuleDirection {
    OUTGOING, INCOMING, BOTH;

    boolean covers(PaymentDirection direction) {
        return this == BOTH || name().equals(direction.name());
    }
}
