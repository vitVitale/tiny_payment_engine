package com.tpe.ledgerservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.ledgerservice.application.idempotency.IdempotencyRetention;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** The idempotency guard of specs/12-ledger-service.md §6.1 on account opening. */
class IdempotencyIT extends LedgerStack {

    /** The advisory lock of the concurrency test. */
    private static final long LATCH = 4_401;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    IdempotencyRetention retention;

    @Test
    void LED_AC_16_sameKeyWithADifferentBodyIs409AndWithTheSameBodyReplaysTheStoredResponse() {
        String key = newIdempotencyKey();
        Map<String, Object> body = person(customerId());

        var first = openAsText(key, body);
        var replayed = openAsText(key, body);
        var conflicting = open(operator("op-1"), key, person(customerId()));

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(replayed.getStatusCode().value()).isEqualTo(201);
        assertThat(replayed.getBody()).isEqualTo(first.getBody());
        assertThat(replayed.getHeaders().getContentType()).isEqualTo(first.getHeaders().getContentType());
        assertThat(conflicting.getStatusCode().value()).isEqualTo(409);
        assertThat(conflicting.getBody().get("code").asString()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void theOrderOfPropertiesDoesNotMakeADifferentRequest() {
        String key = newIdempotencyKey();
        String customerId = customerId();
        var holder = new LinkedHashMap<String, Object>();
        holder.put("type", "PERSON");
        holder.put("name", "Anna Schmidt");
        var reordered = new LinkedHashMap<String, Object>();
        reordered.put("name", "Anna Schmidt");
        reordered.put("type", "PERSON");

        var first = open(operator("op-1"), key, Map.of("customerId", customerId, "holder", holder));
        var retry = open(operator("op-1"), key, Map.of("holder", reordered, "customerId", customerId));

        assertThat(retry.getStatusCode().value()).isEqualTo(201);
        assertThat(retry.getBody()).isEqualTo(first.getBody());
    }

    @Test
    void withoutTheHeaderARequestIs400IdempotencyKeyMissing() {
        String customerId = customerId();

        var missing = open(operator("op-1"), null, person(customerId));
        var blank = open(operator("op-1"), " ", person(customerId));

        assertThat(missing.getStatusCode().value()).isEqualTo(400);
        assertThat(missing.getBody().get("code").asString()).isEqualTo("IDEMPOTENCY_KEY_MISSING");
        assertThat(blank.getStatusCode().value()).isEqualTo(400);
        assertThat(blank.getBody().get("code").asString()).isEqualTo("IDEMPOTENCY_KEY_MISSING");
        assertThat(accounts(customerId)).isZero();
    }

    @Test
    void theKeyIsCheckedBeforeTheBody() {
        var response = open(operator("op-1"), null, person("CUST 000123"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("code").asString()).isEqualTo("IDEMPOTENCY_KEY_MISSING");
    }

    @Test
    void aKeyOf200CharactersIsAcceptedAndOneOf201Is400() {
        String longest = "k".repeat(164) + UUID.randomUUID();
        String tooLong = "k".repeat(201);

        var accepted = open(operator("op-1"), longest, person(customerId()));
        var refused = open(operator("op-1"), tooLong, person(customerId()));

        assertThat(accepted.getStatusCode().value()).isEqualTo(201);
        assertThat(refused.getStatusCode().value()).isEqualTo(400);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(refused.getBody().get("errors").get(0).get("field").asString()).isEqualTo("Idempotency-Key");
    }

    /**
     * A latch in the database: each opening waits in a trigger for an advisory lock the test holds, so both requests
     * are inside the business change before either stores the key. Released together, the second insert of the key
     * waits for the first transaction, then rolls its own opening back and replays.
     */
    @Test
    void twoConcurrentOpensWithTheSameNewKeyCreateOneAccountAndAnswerAlike() throws Exception {
        String customerId = "LATCH-" + customerId();
        String key = newIdempotencyKey();
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION latched_opening() RETURNS trigger AS $$
                BEGIN
                  IF NEW.customer_id LIKE 'LATCH-%%' THEN
                    PERFORM pg_advisory_xact_lock_shared(%d);
                  END IF;
                  RETURN NEW;
                END $$ LANGUAGE plpgsql""".formatted(LATCH)).update();
        jdbc.sql("""
                CREATE TRIGGER latched_opening BEFORE INSERT ON accounts
                FOR EACH ROW EXECUTE FUNCTION latched_opening()""").update();
        List<ResponseEntity<JsonNode>> responses;
        try (Connection latch = dataSource.getConnection(); var threads = Executors.newFixedThreadPool(2)) {
            latch.createStatement().execute("SELECT pg_advisory_lock(" + LATCH + ")");
            List<Future<ResponseEntity<JsonNode>>> calls = List.of(
                    threads.submit(() -> open(operator("op-1"), key, person(customerId))),
                    threads.submit(() -> open(operator("op-2"), key, person(customerId))));
            // both openings are in their transactions, waiting at the latch
            await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql("""
                    SELECT count(*) FROM pg_locks
                     WHERE locktype = 'advisory' AND objid = ? AND NOT granted""")
                    .param(LATCH).query(Long.class).single() == 2);
            latch.createStatement().execute("SELECT pg_advisory_unlock(" + LATCH + ")");
            responses = List.of(calls.get(0).get(), calls.get(1).get());
        } finally {
            jdbc.sql("DROP TRIGGER latched_opening ON accounts").update();
        }

        assertThat(responses).allSatisfy(response -> assertThat(response.getStatusCode().value()).isEqualTo(201));
        assertThat(responses.get(1).getBody()).isEqualTo(responses.get(0).getBody());
        assertThat(accounts(customerId)).isEqualTo(1);
        assertThat(events(customerId)).isEqualTo(1);
        assertThat(auditRows(customerId)).isEqualTo(1);
    }

    @Test
    void a422IsStoredAndReplayed() {
        String key = newIdempotencyKey();
        var withoutCustomerId = new HashMap<>(person("unused"));
        withoutCustomerId.remove("customerId");

        var first = open(operator("op-1"), key, withoutCustomerId);
        var replayed = open(operator("op-1"), key, withoutCustomerId);

        assertThat(first.getStatusCode().value()).isEqualTo(422);
        assertThat(first.getBody().get("code").asString()).isEqualTo("CUSTOMER_ID_MISSING");
        assertThat(replayed.getStatusCode().value()).isEqualTo(422);
        assertThat(replayed.getBody()).isEqualTo(first.getBody());
        assertThat(jdbc.sql("SELECT response_status FROM idempotency_keys WHERE idempotency_key = ?").param(key)
                .query(Integer.class).single()).isEqualTo(422);
    }

    @Test
    void a5xxIsNotStoredAndTheRetryRunsAgain() {
        String key = newIdempotencyKey();
        String customerId = "FAIL-IDEM-" + customerId();
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION fail_idempotent_outbox() RETURNS trigger AS $$
                BEGIN
                  IF NEW.payload -> 'account' ->> 'customerId' LIKE 'FAIL-IDEM-%' THEN
                    RAISE EXCEPTION 'injected outbox failure';
                  END IF;
                  RETURN NEW;
                END $$ LANGUAGE plpgsql""").update();
        jdbc.sql("""
                CREATE TRIGGER fail_idempotent_outbox BEFORE INSERT ON outbox
                FOR EACH ROW EXECUTE FUNCTION fail_idempotent_outbox()""").update();
        ResponseEntity<JsonNode> failed;
        try {
            failed = open(operator("op-1"), key, person(customerId));
        } finally {
            jdbc.sql("DROP TRIGGER fail_idempotent_outbox ON outbox").update();
        }
        var stored = jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE idempotency_key = ?").param(key)
                .query(Long.class).single();

        var retried = open(operator("op-1"), key, person(customerId));

        assertThat(failed.getStatusCode().value()).isEqualTo(500);
        assertThat(stored).isZero();
        assertThat(retried.getStatusCode().value()).isEqualTo(201);
        assertThat(accounts(customerId)).isEqualTo(1);
    }

    @Test
    void aReplayedOpenPublishesNoSecondAccountOpenedAndWritesNoSecondAuditRow() {
        String key = newIdempotencyKey();
        String customerId = customerId();

        open(operator("op-1"), key, person(customerId));
        open(operator("op-1"), key, person(customerId));
        open(operator("op-1"), key, person(customerId));

        assertThat(accounts(customerId)).isEqualTo(1);
        assertThat(events(customerId)).isEqualTo(1);
        assertThat(auditRows(customerId)).isEqualTo(1);
    }

    @Test
    void retentionDeletesOnlyKeysOlderThanThePeriod() {
        Instant now = CLOCK.instant();
        String expired = "retention-expired-" + UUID.randomUUID();
        String kept = "retention-kept-" + UUID.randomUUID();
        insertKey(expired, now.minus(Duration.ofDays(30)).minusSeconds(1));
        insertKey(kept, now.minus(Duration.ofDays(30)).plusSeconds(1));

        retention.purge();

        assertThat(jdbc.sql("SELECT idempotency_key FROM idempotency_keys WHERE idempotency_key IN (?, ?)")
                .params(expired, kept).query(String.class).list()).containsExactly(kept);
    }

    private void insertKey(String key, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO idempotency_keys (idempotency_key, request_hash, response_status, response_body, created_at)
                VALUES (?, ?, 201, '{}', ?)""")
                .params(key, "0".repeat(64), createdAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private ResponseEntity<String> openAsText(String key, Map<String, ?> body) {
        return http().post().uri("/v1/accounts")
                .header(AUTHORIZATION, operator("op-1"))
                .header(IDEMPOTENCY_KEY, key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().toEntity(String.class);
    }

    private long accounts(String customerId) {
        return jdbc.sql("SELECT count(*) FROM accounts WHERE customer_id = ?").param(customerId)
                .query(Long.class).single();
    }

    private long events(String customerId) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE payload -> 'account' ->> 'customerId' = ?")
                .param(customerId).query(Long.class).single();
    }

    private long auditRows(String customerId) {
        return jdbc.sql("SELECT count(*) FROM audit_log WHERE details ->> 'customerId' = ?").param(customerId)
                .query(Long.class).single();
    }

    private static Map<String, Object> person(String customerId) {
        return Map.of("customerId", customerId,
                "holder", Map.of("type", "PERSON", "name", "Anna Schmidt", "dateOfBirth", "1985-04-12"));
    }

    private static String customerId() {
        return "CUST-" + UUID.randomUUID().toString().substring(0, 13);
    }
}
