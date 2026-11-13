package com.tpe.contracts.risk;

/** A rule that hit, with its score in {@code (0, 1]} (specs/14-risk-service.md §3.1). */
public record RuleHit(String ruleId, double score) {}
