package com.tpe.vopservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.vopservice.application.accounts.AccountHolderProjection;
import com.tpe.vopservice.application.references.VopResults;
import com.tpe.vopservice.domain.accounts.AccountHolder;
import com.tpe.vopservice.domain.accounts.AccountHolder.AccountStatus;
import com.tpe.vopservice.domain.accounts.AccountHolder.HolderType;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * The retention job (specs/13-vop-service.md §6.3, Q-09): payee and actual names are cleared once a result is past
 * {@code result-retention} (24 h) after its expiry; rows are deleted after 30 days. Time is the stack's
 * {@code MutableClock}; the job polls every 200 ms.
 */
class RetentionIT extends VopStack {

    private static final String DEBTOR = "DE36100100100000000001";
    private static final Duration VALIDITY = Duration.ofMinutes(30);
    private static final Duration RETENTION = Duration.ofHours(24);

    @Autowired
    AccountHolderProjection projection;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MeterRegistry meters;

    @Autowired
    VopResults results;

    @Test
    void VOP_AC_08_afterRetentionStoredResultsContainNoNames() {
        String reference = issue(holder("Anna Schmidt-Berg"), "Anna Schmitt");
        assertThat(names(reference)).contains(new Names("Anna Schmitt", "Anna Schmidt-Berg"));

        CLOCK.advance(VALIDITY.plus(RETENTION));
        // exactly at the boundary the result is not yet past retention
        pollTwice();
        assertThat(names(reference)).contains(new Names("Anna Schmitt", "Anna Schmidt-Berg"));

        CLOCK.advance(Duration.ofSeconds(1));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(names(reference)).contains(new Names(null, null)));
        // the reference itself stays: payment-api learns it is expired, not unknown
        assertThat(jdbc.sql("SELECT result FROM vop_results WHERE vop_reference = ?").param(reference)
                .query(String.class).single()).isEqualTo("CLOSE_MATCH");
    }

    @Test
    void rowsAreDeletedAfterThirtyDays() {
        String reference = issue(Ibans.own(), "Max Mustermann");

        CLOCK.advance(Duration.ofDays(30).minusSeconds(1));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(names(reference)).contains(new Names(null, null)));
        CLOCK.advance(Duration.ofSeconds(2));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(names(reference)).isEmpty());
        assertThat(check(reference)).isEqualTo("UNKNOWN");
    }

    @Test
    void theOverdueGaugeIsZeroWhileTheJobKeepsUp() {
        issue(Ibans.own(), "Max Mustermann");
        CLOCK.advance(VALIDITY.plus(RETENTION).plus(Duration.ofHours(3)));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(meters.get("tpe.vop.retention.overdue").gauge().value()).isZero());
    }

    @Test
    void theOverdueQueryCountsResultsStillNamedThatExpiredBeforeTheCutOff() {
        String reference = issue(Ibans.own(), "Max Mustermann");
        long before = results.countNamedExpiredBefore(CLOCK.instant().plus(VALIDITY).plusMillis(1));

        assertThat(before).isPositive();
        assertThat(results.countNamedExpiredBefore(CLOCK.instant().plus(VALIDITY)))
                .as("expiring exactly at the cut-off is not before it").isLessThan(before);
        assertThat(names(reference)).isPresent();
    }

    private record Names(String payeeName, String actualName) {}

    private Optional<Names> names(String reference) {
        return jdbc.sql("SELECT payee_name, actual_name FROM vop_results WHERE vop_reference = ?")
                .param(reference)
                .query((rs, n) -> new Names(rs.getString(1), rs.getString(2)))
                .optional();
    }

    /** Long enough for the 200 ms job to have run on the current clock at least twice. */
    private static void pollTwice() {
        try {
            Thread.sleep(600);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String holder(String name) {
        var holder = new AccountHolder(UUID.randomUUID(), Ibans.own(), HolderType.PERSON, name, null,
                AccountStatus.OPEN, 1);
        projection.apply(UUID.randomUUID(), holder);
        return holder.iban();
    }

    private String issue(String payee, String name) {
        Map<String, Object> body = Map.of("debtorIban", DEBTOR, "payeeIban", payee, "payeeName", name);
        ResponseEntity<JsonNode> response = http().post().uri("/v1/verifications")
                .header("Content-Type", "application/json").body(body).retrieve().toEntity(JsonNode.class);
        assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(201);
        return response.getBody().get("vopReference").asString();
    }

    private String check(String reference) {
        Map<String, Object> body = Map.of("debtorIban", DEBTOR, "payeeIban", DEBTOR, "payeeName", "Max");
        return http().post().uri("/v1/verifications/{reference}/check", reference)
                .header("Content-Type", "application/json").body(body).retrieve().toEntity(JsonNode.class)
                .getBody().get("problem").asString();
    }
}
