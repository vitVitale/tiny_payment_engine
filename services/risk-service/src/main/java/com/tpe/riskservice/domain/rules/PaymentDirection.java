package com.tpe.riskservice.domain.rules;

/** A payment as seen from our bank (specs/14-risk-service.md §3.1): on-us payments are assessed in both. */
public enum PaymentDirection { OUTGOING, INCOMING }
