package com.tpe.contracts.risk;

/** A fraud decision (specs/14-risk-service.md §3.1); the orchestrator treats {@code REVIEW} as {@code DENY}. */
public enum RiskDecision { ALLOW, DENY, REVIEW }
