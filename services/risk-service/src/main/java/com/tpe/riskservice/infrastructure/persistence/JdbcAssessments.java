package com.tpe.riskservice.infrastructure.persistence;

import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.riskservice.application.assessment.Assessments;
import com.tpe.riskservice.application.assessment.StoredAssessment;
import com.tpe.riskservice.domain.rules.Decision;
import com.tpe.riskservice.domain.rules.PaymentDirection;
import com.tpe.riskservice.domain.rules.RuleHit;
import com.tpe.riskservice.domain.rules.Verdict;
import java.sql.Timestamp;
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

    private List<RuleHit> hits(String text) {
        return Arrays.asList(json.readValue(text, RuleHit[].class));
    }
}
