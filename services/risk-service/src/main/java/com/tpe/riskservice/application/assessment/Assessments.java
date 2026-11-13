package com.tpe.riskservice.application.assessment;

import com.tpe.riskservice.domain.rules.PaymentToAssess;
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
     * The history of our customer's side of {@code payment} for the rules (specs/14-risk-service.md §6.1), in one
     * round trip: the {@code ALLOW} assessments of the customer's account (the debtor IBAN of an {@code OUTGOING}
     * payment, the creditor IBAN of an {@code INCOMING} one) in the payment's direction in
     * {@code (now − lookback, now]}; for an outgoing payment, whether the debtor paid the creditor before; and when
     * the customer's account was opened, from {@code account_holders}.
     */
    RiskContext history(PaymentToAssess payment, Instant now, Duration lookback);
}
