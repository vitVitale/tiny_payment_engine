package com.tpe.riskservice.domain.rules;

/** Whether a rule hit, and how strongly ({@code score} in {@code (0, 1]}; 1 for a plain threshold rule). */
public record RuleResult(boolean hit, double score) {

    public static final RuleResult NO_HIT = new RuleResult(false, 0);

    public RuleResult {
        if (hit ? !(score > 0 && score <= 1) : score != 0) {
            throw new IllegalArgumentException("a hit scores in (0, 1], no hit scores 0");
        }
    }

    public static RuleResult hit(double score) {
        return new RuleResult(true, score);
    }
}
