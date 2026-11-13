package com.tpe.riskservice.domain.rules;

/** A rule that hit, with its score (specs/14-risk-service.md §3.1 {@code ruleHits}). */
public record RuleHit(String ruleId, double score) {}
