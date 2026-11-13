package com.tpe.riskservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * The {@code rules} table (specs/14-risk-service.md §5, §6.1, §7): seeded with every MVP rule, read into memory and
 * refreshed every {@code tpe.risk.rules.refresh-interval} (30 s) of the service's clock; SLO-RSK-03 reads its age.
 */
class RulesIT extends RiskStack {

    private static final Duration REFRESH = Duration.ofSeconds(30);

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MeterRegistry meters;

    @Test
    void everyMvpRuleIsSeededWithItsDefaults() {
        assertThat(jdbc.sql("""
                        SELECT rule_id || ' ' || direction || ' ' || action || ' ' || enabled || ' ' || parameters::text
                        FROM rules ORDER BY rule_id""").query(String.class).list())
                .containsExactly(
                        "AMOUNT_LIMIT BOTH DENY true {\"maxAmountMinor\": 5000000}",
                        "MULE_NEW_ACCOUNT INCOMING DENY true {\"window\": \"PT24H\", \"maxAccountAge\": \"P7D\", "
                                + "\"maxIncomingCount\": 10}",
                        "NEW_PAYEE_HIGH_AMOUNT OUTGOING REVIEW true {\"minAmountMinor\": 500000}",
                        "NON_EU_SANCTIONS BOTH DENY true {\"lists\": [\"UN\"], \"threshold\": 0.97}",
                        "VELOCITY_AMOUNT_24H OUTGOING DENY true {\"window\": \"PT24H\", \"maxAmountMinor\": 10000000}",
                        "VELOCITY_COUNT_1H OUTGOING DENY true {\"window\": \"PT1H\", \"maxCount\": 20}");
    }

    @Test
    void aChangedParameterTakesEffectWithinTheRefreshInterval() {
        // the rules in memory are fresh: the stopped clock makes no refresh due until it is advanced
        await().atMost(Duration.ofSeconds(10))
                .until(() -> meters.get("tpe.risk.rules.age").gauge().value() < REFRESH.toSeconds());
        try {
            setLimit(100_00);
            // not before the interval has passed on the service's clock
            assertThat(decision(200_00)).isEqualTo("ALLOW");

            CLOCK.advance(REFRESH);

            await().atMost(Duration.ofSeconds(10)).until(() -> decision(200_00).equals("DENY"));
            assertThat(meters.get("tpe.risk.rules.age").gauge().value()).isLessThan(REFRESH.toSeconds());
        } finally {
            setLimit(5_000_000);
            CLOCK.advance(REFRESH);
            await().atMost(Duration.ofSeconds(10)).until(() -> decision(200_00).equals("ALLOW"));
        }
    }

    @Test
    void theRulesAgeGrowsWithTheClockUntilTheNextRefresh() {
        await().atMost(Duration.ofSeconds(10))
                .until(() -> meters.get("tpe.risk.rules.age").gauge().value() < REFRESH.toSeconds());
        double age = meters.get("tpe.risk.rules.age").gauge().value();

        CLOCK.advance(Duration.ofSeconds(10));

        assertThat(meters.get("tpe.risk.rules.age").gauge().value()).isGreaterThanOrEqualTo(age + 10);
    }

    private void setLimit(long maxAmountMinor) {
        jdbc.sql("""
                        UPDATE rules SET parameters = jsonb_build_object('maxAmountMinor', ?::bigint),
                                         updated_at = now(), updated_by = 'RulesIT'
                        WHERE rule_id = 'AMOUNT_LIMIT'""")
                .param(maxAmountMinor).update();
    }

    private String decision(long amountMinor) {
        ResponseEntity<JsonNode> response = http().post().uri("/v1/risk/assessments")
                .header("Content-Type", "application/json")
                .body(AssessmentIT.request(UUID.randomUUID(), "INCOMING", amountMinor))
                .retrieve().toEntity(JsonNode.class);
        return response.getBody().get("decision").asString();
    }
}
