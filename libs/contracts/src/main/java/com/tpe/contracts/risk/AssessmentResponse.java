package com.tpe.contracts.risk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tpe.paymentdomain.StatusReason;
import java.time.Instant;
import java.util.List;

/**
 * {@code 200} of {@code POST /v1/risk/assessments} (specs/14-risk-service.md §3.1): the stored decision of the
 * assessment.
 *
 * @param reasonCode {@code FR01} for fraud rules, {@code RR04} for a non-EU sanctions hit; absent for {@code ALLOW}
 */
public record AssessmentResponse(
        String assessmentId,
        RiskDecision decision,
        @JsonInclude(JsonInclude.Include.NON_NULL) StatusReason reasonCode,
        List<RuleHit> ruleHits,
        Instant assessedAt) {}
