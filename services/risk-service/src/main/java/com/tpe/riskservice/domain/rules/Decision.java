package com.tpe.riskservice.domain.rules;

/** A fraud decision (specs/14-risk-service.md §3.1); the orchestrator treats {@code REVIEW} as {@code DENY}. */
public enum Decision { ALLOW, DENY, REVIEW }
