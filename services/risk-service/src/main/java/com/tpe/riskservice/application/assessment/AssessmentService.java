package com.tpe.riskservice.application.assessment;

import com.tpe.riskservice.domain.rules.PaymentToAssess;
import com.tpe.riskservice.domain.rules.RuleEngine;
import com.tpe.riskservice.domain.rules.Verdict;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Fraud assessment of a payment (specs/14-risk-service.md §3.1, §6.1): idempotent by {@code assessmentId}, the rules
 * run in memory on the parameters of the {@link RuleBook}; the only I/O is the local database (no outbound calls).
 * Two concurrent first requests for one id both decide, one stores, and both answer the stored row.
 */
@Service
public class AssessmentService {

    private final Assessments assessments;
    private final RuleBook rules;
    private final RuleEngine engine;
    private final AssessmentMetrics metrics;
    private final Clock clock;

    public AssessmentService(Assessments assessments, RuleBook rules, RuleEngine engine, AssessmentMetrics metrics,
            Clock clock) {
        this.assessments = assessments;
        this.rules = rules;
        this.engine = engine;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** The stored assessment of {@code assessmentId}: decided now if there is none (RSK-AC-02). */
    public StoredAssessment assess(String assessmentId, UUID paymentId, PaymentToAssess payment) {
        AssessmentMetrics.Timing timing = metrics.start();
        StoredAssessment stored = assessments.find(assessmentId).orElseGet(() -> {
            Verdict verdict = engine.decide(payment, rules.current());
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            StoredAssessment decided = new StoredAssessment(assessmentId, paymentId, payment.direction(),
                    payment.debtorIban(), payment.creditorIban(), payment.amount(), verdict, now);
            return assessments.insertIfAbsent(decided) ? decided : stored(assessmentId);
        });
        timing.stop(stored.direction(), stored.verdict().decision());
        return stored;
    }

    private StoredAssessment stored(String assessmentId) {
        Optional<StoredAssessment> stored = assessments.find(assessmentId);
        return stored.orElseThrow(() -> new IllegalStateException(
                "assessment " + assessmentId + " was stored concurrently but cannot be read"));
    }
}
