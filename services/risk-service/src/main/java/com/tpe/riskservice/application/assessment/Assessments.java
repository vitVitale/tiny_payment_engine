package com.tpe.riskservice.application.assessment;

import com.tpe.paymentdomain.Iban;
import com.tpe.riskservice.domain.rules.RiskContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** {@code assessments} (specs/14-risk-service.md §5). */
public interface Assessments {

    Optional<StoredAssessment> find(String assessmentId);

    /** Stores {@code assessment} unless its id is stored already; {@code true} if it was stored now. */
    boolean insertIfAbsent(StoredAssessment assessment);

    /**
     * The payer's history for the velocity and new-payee rules (specs/14-risk-service.md §6.1), in one round trip:
     * the {@code ALLOW OUTGOING} assessments of {@code debtor} in {@code (now − lookback, now]}, and whether there is
     * one of {@code debtor} to {@code creditor} at any time.
     */
    RiskContext history(Iban debtor, Iban creditor, Instant now, Duration lookback);
}
