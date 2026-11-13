package com.tpe.riskservice;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.spring.junit5.PactVerificationSpringProvider;
import com.tpe.testsupport.pact.PactVerification;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * risk-service verifies every collected pact that names it (specs/20-testing-strategy.md §10.3): P-03 of
 * payment-orchestrator, against the running service with the seeded rules. Runs only with {@code -Ppact-verify}.
 */
@Provider("risk-service")
@PactVerification
class RiskServicePactVerificationIT extends RiskStack {

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void target(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget("localhost", port));
    }

    @TestTemplate
    @ExtendWith(PactVerificationSpringProvider.class)
    void PACT_P03_verify(PactVerificationContext context) {
        context.verifyInteraction();
    }

    /** The rules of V1, which the interaction's amount, payee and {@code vopResult} trip or not. */
    @State("the seeded MVP rules apply")
    void theSeededRulesApply() {}

    /**
     * A decision stored for the payment, which a repeated {@code assessmentId} answers (§3.1). It is also the only way
     * to {@code RR04} until the sanctions track imports the non-EU lists ({@code NON_EU_SANCTIONS} never hits before),
     * and to an incoming {@code REVIEW}, which no seeded rule gives; written directly, as no application service
     * stores a decision without deciding it.
     */
    @State("the payment is already assessed")
    void thePaymentIsAlreadyAssessed(Map<String, Object> stored) {
        UUID paymentId = UUID.fromString((String) stored.get("paymentId"));
        String direction = (String) stored.get("direction");
        jdbc.sql("""
                        INSERT INTO assessments (assessment_id, payment_id, direction, debtor_iban, creditor_iban,
                                                 amount_minor, decision, reason_code, rule_hits, assessed_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, '[]'::jsonb, ?)
                        ON CONFLICT (assessment_id) DO NOTHING""")
                .param(paymentId + ":" + direction)
                .param(paymentId)
                .param(direction)
                .param(stored.get("debtorIban"))
                .param(stored.get("creditorIban"))
                .param(((Number) stored.get("amountMinor")).longValue())
                .param(stored.get("decision"))
                .param(stored.get("reasonCode"))
                .param(Timestamp.from(CLOCK.instant()))
                .update();
    }
}
