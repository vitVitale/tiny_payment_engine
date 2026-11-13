package com.tpe.riskservice;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * {@code POST /v1/risk/assessments} (specs/14-risk-service.md §3.1, §6.1): the seeded {@code AMOUNT_LIMIT} of
 * 50 000.00 EUR in both directions, idempotency by {@code assessmentId}, the {@code vopResult} rule and the stored row.
 */
class AssessmentIT extends RiskStack {

    private static final String DEBTOR = "DE36100100100000000001";
    private static final String CREDITOR = "DE07200200200123456789";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MeterRegistry meters;

    @ParameterizedTest
    @ValueSource(strings = {"OUTGOING", "INCOMING"})
    void anAmountUpToTheLimitIsAllowed(String direction) {
        ResponseEntity<JsonNode> response = assess(request(UUID.randomUUID(), direction, 5_000_000));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("decision").asString()).isEqualTo("ALLOW");
        assertThat(response.getBody().has("reasonCode")).isFalse();
        assertThat(response.getBody().get("ruleHits").size()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"OUTGOING", "INCOMING"})
    void anAmountAboveTheLimitIsDeniedFr01(String direction) {
        ResponseEntity<JsonNode> response = assess(request(UUID.randomUUID(), direction, 5_000_001));

        assertThat(response.getBody().get("decision").asString()).isEqualTo("DENY");
        assertThat(response.getBody().get("reasonCode").asString()).isEqualTo("FR01");
        assertThat(response.getBody().get("ruleHits").toString())
                .isEqualTo("[{\"ruleId\":\"AMOUNT_LIMIT\",\"score\":1.0}]");
    }

    @Test
    void RSK_AC_02_theSameAssessmentIdTwiceAnswersTheStoredDecisionWithOneRow() {
        UUID payment = UUID.randomUUID();
        ResponseEntity<JsonNode> first = assess(request(payment, "OUTGOING", 100_00));
        CLOCK.advance(java.time.Duration.ofSeconds(5));

        // a replay with a different amount still gets the stored decision
        ResponseEntity<JsonNode> again = assess(request(payment, "OUTGOING", 9_000_000));

        assertThat(again.getStatusCode().value()).isEqualTo(200);
        assertThat(again.getBody()).isEqualTo(first.getBody());
        assertThat(again.getBody().get("decision").asString()).isEqualTo("ALLOW");
        assertThat(jdbc.sql("SELECT count(*) FROM assessments WHERE payment_id = ?").param(payment)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void anOnUsPaymentIsAssessedOncePerDirection() {
        UUID payment = UUID.randomUUID();

        assess(request(payment, "OUTGOING", 100_00));
        assess(request(payment, "INCOMING", 100_00));

        assertThat(jdbc.sql("SELECT assessment_id FROM assessments WHERE payment_id = ? ORDER BY assessment_id")
                .param(payment).query(String.class).list())
                .containsExactly(payment + ":INCOMING", payment + ":OUTGOING");
    }

    @Test
    void theAssessmentIsStoredWithItsDecisionAndHits() {
        UUID payment = UUID.randomUUID();

        ResponseEntity<JsonNode> response = assess(request(payment, "INCOMING", 6_000_000));

        Map<String, Object> row = jdbc.sql("""
                        SELECT direction, debtor_iban, creditor_iban, amount_minor, decision, reason_code,
                               rule_hits::text AS hits, assessed_at
                        FROM assessments WHERE assessment_id = ?""")
                .param(payment + ":INCOMING").query().singleRow();
        assertThat(row.get("direction")).isEqualTo("INCOMING");
        assertThat(row.get("debtor_iban")).isEqualTo(DEBTOR);
        assertThat(row.get("creditor_iban")).isEqualTo(CREDITOR);
        assertThat(row.get("amount_minor")).isEqualTo(6_000_000L);
        assertThat(row.get("decision")).isEqualTo("DENY");
        assertThat(row.get("reason_code")).isEqualTo("FR01");
        assertThat(row.get("hits")).isEqualTo("[{\"score\": 1.0, \"ruleId\": \"AMOUNT_LIMIT\"}]");
        assertThat(((Timestamp) row.get("assessed_at")).toInstant().toString())
                .isEqualTo(response.getBody().get("assessedAt").asString());
    }

    @Test
    void anOutgoingRequestWithoutVopResultIs400() {
        Map<String, Object> body = request(UUID.randomUUID(), "OUTGOING", 100_00);
        body.remove("vopResult");

        ResponseEntity<JsonNode> response = assess(body);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().get("errors").toString())
                .isEqualTo("[{\"field\":\"vopResult\",\"code\":\"REQUIRED\"}]");
    }

    @Test
    void anIncomingRequestWithoutVopResultIsAssessed() {
        Map<String, Object> body = request(UUID.randomUUID(), "INCOMING", 100_00);
        body.remove("vopResult");

        assertThat(assess(body).getBody().get("decision").asString()).isEqualTo("ALLOW");
    }

    @Test
    void invalidFieldsAre400ValidationFailed() {
        UUID payment = UUID.randomUUID();
        Map<String, Object> body = request(payment, "OUTGOING", 100_00);
        body.put("assessmentId", payment + ":INCOMING");
        body.put("debtor", Map.of("name", "Anna Schmidt"));
        body.remove("creditor");

        ResponseEntity<JsonNode> response = assess(body);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("errors").toString())
                .contains("{\"field\":\"assessmentId\",\"code\":\"INVALID_ASSESSMENT_ID\"}")
                .contains("{\"field\":\"debtor.iban\",\"code\":\"REQUIRED\"}")
                .contains("{\"field\":\"creditor\",\"code\":\"REQUIRED\"}");

        Map<String, Object> badIban = request(UUID.randomUUID(), "OUTGOING", 100_00);
        badIban.put("creditor", Map.of("name", "Max", "iban", "DE00200200200123456789"));
        assertThat(assess(badIban).getBody().get("errors").toString())
                .contains("{\"field\":\"creditor.iban\",\"code\":\"INVALID_IBAN\"}");

        // the amount is an unwrapped Money: a refused one is a 400 without a field path
        Map<String, Object> noAmount = request(UUID.randomUUID(), "OUTGOING", 100_00);
        noAmount.remove("amountMinor");
        noAmount.remove("currency");
        assertThat(assess(noAmount).getStatusCode().value()).isEqualTo(400);
        Map<String, Object> noMinor = request(UUID.randomUUID(), "OUTGOING", 100_00);
        noMinor.remove("amountMinor");
        assertThat(assess(noMinor).getStatusCode().value()).isEqualTo(400);
        Map<String, Object> dollars = request(UUID.randomUUID(), "OUTGOING", 100_00);
        dollars.put("currency", "USD");
        ResponseEntity<JsonNode> notEur = assess(dollars);
        assertThat(notEur.getStatusCode().value()).isEqualTo(400);
        assertThat(notEur.getBody().get("code").asString()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void theDurationIsTimedByDirectionAndDecision() {
        double before = count("OUTGOING", "DENY");

        assess(request(UUID.randomUUID(), "OUTGOING", 9_000_000));

        assertThat(count("OUTGOING", "DENY")).isEqualTo(before + 1);
    }

    private double count(String direction, String decision) {
        Timer timer = meters.find("tpe.risk.assessment.duration").tags("direction", direction, "decision", decision)
                .timer();
        return timer == null ? 0 : timer.count();
    }

    static Map<String, Object> request(UUID payment, String direction, long amountMinor) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assessmentId", payment + ":" + direction);
        body.put("paymentId", payment.toString());
        body.put("direction", direction);
        body.put("txId", "TX" + payment.toString().replace("-", "").substring(0, 30));
        body.put("amountMinor", amountMinor);
        body.put("currency", "EUR");
        body.put("debtor", Map.of("name", "Anna Schmidt", "iban", DEBTOR));
        body.put("creditor", Map.of("name", "Max Mustermann", "iban", CREDITOR));
        body.put("debtorAgentBic", "TPEBDEFFXXX");
        body.put("creditorAgentBic", "PEERDEFFXXX");
        body.put("remittanceInformation", "Invoice 7");
        body.put("vopResult", "MATCH");
        return body;
    }

    ResponseEntity<JsonNode> assess(Map<String, Object> body) {
        return http().post().uri("/v1/risk/assessments")
                .header("Content-Type", "application/json").body(body).retrieve().toEntity(JsonNode.class);
    }
}
