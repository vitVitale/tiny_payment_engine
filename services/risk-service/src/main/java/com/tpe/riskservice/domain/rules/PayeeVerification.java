package com.tpe.riskservice.domain.rules;

/** The VoP result the payer saw for an outgoing payment (specs/14-risk-service.md §3.1, UC-04 2b). */
public enum PayeeVerification { MATCH, CLOSE_MATCH, NO_MATCH, NOT_POSSIBLE }
