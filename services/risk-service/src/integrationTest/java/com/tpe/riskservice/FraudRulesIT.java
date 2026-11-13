package com.tpe.riskservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.riskservice.application.accounts.AccountHolderProjection;
import com.tpe.riskservice.domain.accounts.AccountHolder;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The seeded rules over our customer's stored assessments and account (specs/14-risk-service.md §6.1, UC-04 2b):
 * {@code VELOCITY_COUNT_1H} (> 20 per debtor IBAN in 1 h), {@code VELOCITY_AMOUNT_24H} (> 100 000.00 EUR per debtor
 * IBAN in 24 h), {@code NEW_PAYEE_HIGH_AMOUNT} (first payment to the creditor IBAN, > 5 000.00 EUR, not a VoP
 * {@code MATCH}) and {@code MULE_NEW_ACCOUNT} (incoming to an account opened < 7 days ago, > 10 in 24 h); only
 * {@code ALLOW} assessments count. Every test has customers of its own.
 */
class FraudRulesIT extends RiskStack {

    @Autowired
    AccountHolderProjection projection;

    @Test
    void RSK_AC_01_theTwentiethPaymentOfAPayerWithinAnHourIsAllowedTheTwentyFirstDenied() {
        String debtor = Ibans.fresh();
        for (int i = 0; i < 20; i++) {
            assertThat(decision(assess(outgoing(debtor, Ibans.fresh(), 100_00, "MATCH")))).isEqualTo("ALLOW");
        }

        ResponseEntity<JsonNode> twentyFirst = assess(outgoing(debtor, Ibans.fresh(), 100_00, "MATCH"));

        assertThat(decision(twentyFirst)).isEqualTo("DENY");
        assertThat(twentyFirst.getBody().get("reasonCode").asString()).isEqualTo("FR01");
        assertThat(twentyFirst.getBody().get("ruleHits").toString())
                .isEqualTo("[{\"ruleId\":\"VELOCITY_COUNT_1H\",\"score\":1.0}]");

        // the denied one did not count: an hour after the first twenty, the payer may pay again
        CLOCK.advance(Duration.ofHours(1));
        assertThat(decision(assess(outgoing(debtor, Ibans.fresh(), 100_00, "MATCH")))).isEqualTo("ALLOW");
    }

    @Test
    void RSK_AC_01_reachingOneHundredThousandEuroWithin24HoursIsAllowedGoingAboveDenied() {
        String debtor = Ibans.fresh();
        assertThat(decision(assess(outgoing(debtor, Ibans.fresh(), 5_000_000, "MATCH")))).isEqualTo("ALLOW");
        CLOCK.advance(Duration.ofHours(23));
        assertThat(decision(assess(outgoing(debtor, Ibans.fresh(), 5_000_000, "MATCH")))).isEqualTo("ALLOW");

        ResponseEntity<JsonNode> above = assess(outgoing(debtor, Ibans.fresh(), 1, "MATCH"));

        assertThat(decision(above)).isEqualTo("DENY");
        assertThat(above.getBody().get("ruleHits").toString())
                .isEqualTo("[{\"ruleId\":\"VELOCITY_AMOUNT_24H\",\"score\":1.0}]");

        // 24 h after the first payment it no longer counts
        CLOCK.advance(Duration.ofHours(1));
        assertThat(decision(assess(outgoing(debtor, Ibans.fresh(), 1, "MATCH")))).isEqualTo("ALLOW");
    }

    @Test
    void aDeniedAssessmentDoesNotMoveTheVelocityAggregates() {
        String debtor = Ibans.fresh();
        assertThat(decision(assess(outgoing(debtor, Ibans.fresh(), 5_000_000, "MATCH")))).isEqualTo("ALLOW");
        // denied by AMOUNT_LIMIT: had it counted, the next 50 000.00 EUR would be above 100 000.00 EUR in 24 h
        assertThat(decision(assess(outgoing(debtor, Ibans.fresh(), 6_000_000, "MATCH")))).isEqualTo("DENY");

        assertThat(decision(assess(outgoing(debtor, Ibans.fresh(), 5_000_000, "MATCH")))).isEqualTo("ALLOW");
    }

    @Test
    void RSK_AC_11_aFirstPaymentOf6000EuroToAMatchedPayeeIsAllowed() {
        ResponseEntity<JsonNode> response = assess(outgoing(Ibans.fresh(), Ibans.fresh(), 600_000, "MATCH"));

        assertThat(decision(response)).isEqualTo("ALLOW");
        assertThat(response.getBody().get("ruleHits").size()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLOSE_MATCH", "NO_MATCH", "NOT_POSSIBLE"})
    void RSK_AC_11_aFirstPaymentOf6000EuroToAPayeeNotMatchedIsReview(String vopResult) {
        ResponseEntity<JsonNode> response = assess(outgoing(Ibans.fresh(), Ibans.fresh(), 600_000, vopResult));

        assertThat(decision(response)).isEqualTo("REVIEW");
        assertThat(response.getBody().get("reasonCode").asString()).isEqualTo("FR01");
        assertThat(response.getBody().get("ruleHits").toString())
                .isEqualTo("[{\"ruleId\":\"NEW_PAYEE_HIGH_AMOUNT\",\"score\":1.0}]");
    }

    @Test
    void aPayeeThePayerHasPaidBeforeIsNoLongerNew() {
        String debtor = Ibans.fresh();
        String creditor = Ibans.fresh();
        assertThat(decision(assess(outgoing(debtor, creditor, 100_00, "NO_MATCH")))).isEqualTo("ALLOW");

        assertThat(decision(assess(outgoing(debtor, creditor, 600_000, "NO_MATCH")))).isEqualTo("ALLOW");
        // new to another payer of the same creditor
        assertThat(decision(assess(outgoing(Ibans.fresh(), creditor, 600_000, "NO_MATCH")))).isEqualTo("REVIEW");
    }

    @Test
    void aReviewedPaymentDoesNotMakeThePayeeKnown() {
        String debtor = Ibans.fresh();
        String creditor = Ibans.fresh();
        assertThat(decision(assess(outgoing(debtor, creditor, 600_000, "NO_MATCH")))).isEqualTo("REVIEW");

        assertThat(decision(assess(outgoing(debtor, creditor, 600_000, "NO_MATCH")))).isEqualTo("REVIEW");
    }

    @Test
    void anIncomingPaymentIsNotCheckedAgainstThePayersHistory() {
        String debtor = Ibans.fresh();
        for (int i = 0; i < 20; i++) {
            assess(outgoing(debtor, Ibans.fresh(), 100_00, "MATCH"));
        }
        Map<String, Object> incoming = AssessmentIT.request(UUID.randomUUID(), "INCOMING", 600_000);
        incoming.put("debtor", Map.of("name", "Anna Schmidt", "iban", debtor));
        incoming.remove("vopResult");

        // the payer's 21st payment within the hour, to a new payee above 5 000.00 EUR, without a VoP result
        assertThat(decision(assess(incoming))).isEqualTo("ALLOW");
    }

    @Test
    void RSK_AC_01_theEleventhIncomingPaymentWithin24HoursToAnAccountOpenedThisWeekIsDenied() {
        String creditor = holder(CLOCK.instant().minus(Duration.ofDays(6)));
        for (int i = 0; i < 10; i++) {
            assertThat(decision(assess(incoming(Ibans.fresh(), creditor, 100_00)))).isEqualTo("ALLOW");
        }

        ResponseEntity<JsonNode> eleventh = assess(incoming(Ibans.fresh(), creditor, 100_00));

        assertThat(decision(eleventh)).isEqualTo("DENY");
        assertThat(eleventh.getBody().get("reasonCode").asString()).isEqualTo("FR01");
        assertThat(eleventh.getBody().get("ruleHits").toString())
                .isEqualTo("[{\"ruleId\":\"MULE_NEW_ACCOUNT\",\"score\":1.0}]");
        // the denied one did not count, and a day later the window is empty again
        CLOCK.advance(Duration.ofHours(24));
        assertThat(decision(assess(incoming(Ibans.fresh(), creditor, 100_00)))).isEqualTo("ALLOW");
    }

    @Test
    void RSK_AC_01_anAccountOpenedSevenDaysAgoTakesAnyNumberOfIncomingPayments() {
        String creditor = holder(CLOCK.instant().minus(Duration.ofDays(7)));

        for (int i = 0; i < 11; i++) {
            assertThat(decision(assess(incoming(Ibans.fresh(), creditor, 100_00)))).isEqualTo("ALLOW");
        }
    }

    @Test
    void anUnknownCreditorAccountNeverTripsTheMuleRule() {
        String unknown = Ibans.fresh();

        for (int i = 0; i < 11; i++) {
            assertThat(decision(assess(incoming(Ibans.fresh(), unknown, 100_00)))).isEqualTo("ALLOW");
        }
    }

    /** An OPEN person's account opened at {@code openedAt}, in the projection; its IBAN. */
    private String holder(Instant openedAt) {
        String iban = Ibans.fresh();
        projection.apply(UUID.randomUUID(), new AccountHolder(UUID.randomUUID(), "CUST-" + iban, iban,
                AccountHolder.HolderType.PERSON, "Max Mustermann", null, "DE", null,
                AccountHolder.AccountStatus.OPEN, openedAt, 1));
        return iban;
    }

    private static Map<String, Object> incoming(String debtor, String creditor, long amountMinor) {
        Map<String, Object> body = AssessmentIT.request(UUID.randomUUID(), "INCOMING", amountMinor);
        body.put("debtor", Map.of("name", "Anna Schmidt", "iban", debtor));
        body.put("creditor", Map.of("name", "Max Mustermann", "iban", creditor));
        body.remove("vopResult");
        return body;
    }

    private static Map<String, Object> outgoing(String debtor, String creditor, long amountMinor, String vopResult) {
        Map<String, Object> body = AssessmentIT.request(UUID.randomUUID(), "OUTGOING", amountMinor);
        body.put("debtor", Map.of("name", "Anna Schmidt", "iban", debtor));
        body.put("creditor", Map.of("name", "Max Mustermann", "iban", creditor));
        body.put("vopResult", vopResult);
        return body;
    }

    private static String decision(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(200);
        return response.getBody().get("decision").asString();
    }

    private ResponseEntity<JsonNode> assess(Map<String, Object> body) {
        return http().post().uri("/v1/risk/assessments")
                .header("Content-Type", "application/json").body(body).retrieve().toEntity(JsonNode.class);
    }
}
