package com.tpe.riskservice.infrastructure.persistence;

import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.riskservice.application.assessment.Assessments;
import com.tpe.riskservice.application.assessment.StoredAssessment;
import com.tpe.riskservice.domain.rules.Decision;
import com.tpe.riskservice.domain.rules.PastPayment;
import com.tpe.riskservice.domain.rules.PaymentDirection;
import com.tpe.riskservice.domain.rules.RiskContext;
import com.tpe.riskservice.domain.rules.RuleHit;
import com.tpe.riskservice.domain.rules.Verdict;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** {@code assessments} in PostgreSQL; {@code rule_hits} as a JSON array of {@code {ruleId, score}}. */
@Repository
class JdbcAssessments implements Assessments {

    private static final Currency EUR = Currency.getInstance("EUR");

    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    JdbcAssessments(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StoredAssessment> find(String assessmentId) {
        return jdbc.sql("""
                        SELECT assessment_id, payment_id, direction, debtor_iban, creditor_iban, amount_minor,
                               decision, reason_code, rule_hits::text AS rule_hits, assessed_at
                        FROM assessments WHERE assessment_id = ?""")
                .param(assessmentId)
                .query((rs, n) -> new StoredAssessment(
                        rs.getString("assessment_id"),
                        rs.getObject("payment_id", java.util.UUID.class),
                        PaymentDirection.valueOf(rs.getString("direction")),
                        new Iban(rs.getString("debtor_iban")),
                        new Iban(rs.getString("creditor_iban")),
                        new Money(rs.getLong("amount_minor"), EUR),
                        new Verdict(Decision.valueOf(rs.getString("decision")),
                                rs.getString("reason_code") == null ? null : ReasonCode.valueOf(rs.getString("reason_code")),
                                hits(rs.getString("rule_hits"))),
                        rs.getTimestamp("assessed_at").toInstant()))
                .optional();
    }

    @Override
    public boolean insertIfAbsent(StoredAssessment assessment) {
        Verdict verdict = assessment.verdict();
        return jdbc.sql("""
                        INSERT INTO assessments (assessment_id, payment_id, direction, debtor_iban, creditor_iban,
                                                 amount_minor, decision, reason_code, rule_hits, assessed_at)
                        VALUES (:assessmentId, :paymentId, :direction, :debtorIban, :creditorIban, :amountMinor,
                                :decision, :reasonCode, :ruleHits::jsonb, :assessedAt)
                        ON CONFLICT (assessment_id) DO NOTHING""")
                .param("assessmentId", assessment.assessmentId())
                .param("paymentId", assessment.paymentId())
                .param("direction", assessment.direction().name())
                .param("debtorIban", assessment.debtorIban().value())
                .param("creditorIban", assessment.creditorIban().value())
                .param("amountMinor", assessment.amount().minor())
                .param("decision", verdict.decision().name())
                .param("reasonCode", verdict.reasonCode() == null ? null : verdict.reasonCode().name())
                .param("ruleHits", json.writeValueAsString(verdict.ruleHits()))
                .param("assessedAt", Timestamp.from(assessment.assessedAt()))
                .update() == 1;
    }

    /**
     * One statement: the debtor's {@code ALLOW OUTGOING} rows in the window by {@code assessments_debtor_time}, left
     * joined to a single row saying whether the debtor paid the creditor before ({@code assessments_debtor_payee});
     * without rows in the window it answers that single row alone.
     */
    @Override
    public RiskContext history(Iban debtor, Iban creditor, Instant now, Duration lookback) {
        return jdbc.sql("""
                        SELECT p.paid_before, a.assessed_at, a.amount_minor
                        FROM (SELECT EXISTS (SELECT 1 FROM assessments
                                             WHERE debtor_iban = :debtor AND creditor_iban = :creditor
                                               AND direction = 'OUTGOING' AND decision = 'ALLOW') AS paid_before) p
                        LEFT JOIN assessments a
                               ON a.debtor_iban = :debtor AND a.direction = 'OUTGOING' AND a.decision = 'ALLOW'
                              AND a.assessed_at > :since AND a.assessed_at <= :now""")
                .param("debtor", debtor.value())
                .param("creditor", creditor.value())
                .param("since", Timestamp.from(now.minus(lookback)))
                .param("now", Timestamp.from(now))
                .query((ResultSet rs) -> {
                    List<PastPayment> payments = new ArrayList<>();
                    boolean paidBefore = false;
                    while (rs.next()) {
                        paidBefore = rs.getBoolean("paid_before");
                        Timestamp at = rs.getTimestamp("assessed_at");
                        if (at != null) {
                            payments.add(new PastPayment(at.toInstant(), rs.getLong("amount_minor")));
                        }
                    }
                    return RiskContext.loaded(now, lookback, payments, paidBefore);
                });
    }

    private List<RuleHit> hits(String text) {
        return Arrays.asList(json.readValue(text, RuleHit[].class));
    }
}
