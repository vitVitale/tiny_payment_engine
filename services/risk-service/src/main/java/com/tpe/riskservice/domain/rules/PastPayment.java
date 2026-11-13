package com.tpe.riskservice.domain.rules;

import java.time.Instant;
import java.util.Objects;

/** An {@code ALLOW} outgoing assessment of the payer, as the velocity rules count it (specs/14-risk-service.md §6.1). */
public record PastPayment(Instant assessedAt, long amountMinor) {

    public PastPayment {
        Objects.requireNonNull(assessedAt, "assessedAt");
    }
}
