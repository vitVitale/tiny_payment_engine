package com.tpe.ledgerservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.contracts.TechnicalAccounts;
import com.tpe.ledgerservice.application.reservation.TombstoneRetention;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Holds of the Posting API (specs/12-ledger-service.md §3.1, ADR-005) and their tombstones (ADR-009). The Posting API
 * is {@code @InternalApi}: no token until M5 (00 §12.1), so no request here carries one.
 */
class HoldIT extends LedgerStack {

    /** The advisory lock of the release-before-create race. */
    private static final long LATCH = 4_701;
    private static final AtomicInteger CUSTOMERS = new AtomicInteger();

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    TombstoneRetention tombstoneRetention;

    @Test
    void LED_AC_02_aHoldReducesAvailableButNotBookedAndReleaseRestoresAvailable() {
        Funded payer = fundedCustomer(10_000);
        UUID holdId = UUID.randomUUID();

        var created = createHold(holdId, payer.iban(), 3_000);

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(created.getBody().get("accountId").asString()).isEqualTo(payer.accountId().toString());
        assertThat(created.getBody().get("amountMinor").asLong()).isEqualTo(3_000);
        assertBalances(payer.accountId(), 10_000, 7_000);

        var released = release(holdId);

        assertThat(released.getStatusCode().value()).isEqualTo(200);
        assertThat(released.getBody().get("status").asString()).isEqualTo("RELEASED");
        assertThat(released.getBody().get("tombstone").asBoolean()).isFalse();
        assertBalances(payer.accountId(), 10_000, 10_000);
    }

    @Test
    void LED_AC_03_aHoldLargerThanTheAvailableBalanceFailsWithInsufficientFundsAndChangesNothing() {
        Funded payer = fundedCustomer(10_000);
        createHold(UUID.randomUUID(), payer.iban(), 4_000);
        UUID holdId = UUID.randomUUID();

        var refused = createHold(holdId, payer.iban(), 6_001);

        assertProblem(refused, 422, "INSUFFICIENT_FUNDS");
        assertThat(holdRows(holdId)).isZero();
        assertBalances(payer.accountId(), 10_000, 6_000);
    }

    @Test
    void LED_AC_04_releasingAnUnknownHoldCreatesATombstoneAndALaterCreateWithThatIdFailsHoldReleased() {
        Funded payer = fundedCustomer(10_000);
        UUID holdId = UUID.randomUUID();

        var released = release(holdId);

        assertThat(released.getStatusCode().value()).isEqualTo(200);
        assertThat(released.getBody().get("status").asString()).isEqualTo("RELEASED");
        assertThat(released.getBody().get("tombstone").asBoolean()).isTrue();
        assertThat(released.getBody().has("accountId")).isFalse();
        assertThat(released.getBody().has("amountMinor")).isFalse();

        var lateCreate = createHold(holdId, payer.iban(), 1_000);

        assertProblem(lateCreate, 409, "HOLD_RELEASED");
        assertBalances(payer.accountId(), 10_000, 10_000);
        JsonNode read = get(holdId).getBody();
        assertThat(read.get("tombstone").asBoolean()).isTrue();
        assertThat(read.get("status").asString()).isEqualTo("RELEASED");
    }

    /** The hold half of LED-AC-17, which stays pending until pending credits have theirs. */
    @Test
    void aCustomerAccountWithoutCustomerIdMakesHoldCreationFailCustomerIdMissing() {
        Funded payer = fundedCustomer(10_000);
        String constraint = jdbc.sql("""
                SELECT conname FROM pg_constraint
                 WHERE conrelid = 'accounts'::regclass AND pg_get_constraintdef(oid) LIKE '%customer_id IS NOT NULL%'""")
                .query(String.class).single();
        // a row the constraint never saw, as after a migration that added it NOT VALID (12 §5)
        jdbc.sql("ALTER TABLE accounts DROP CONSTRAINT " + constraint).update();
        try {
            jdbc.sql("UPDATE accounts SET customer_id = NULL WHERE account_id = ?").param(payer.accountId()).update();
        } finally {
            jdbc.sql("ALTER TABLE accounts ADD CONSTRAINT " + constraint
                    + " CHECK (kind = 'TECHNICAL' OR customer_id IS NOT NULL) NOT VALID").update();
        }
        UUID holdId = UUID.randomUUID();

        var refused = createHold(holdId, payer.iban(), 100);

        assertProblem(refused, 422, "CUSTOMER_ID_MISSING");
        assertThat(holdRows(holdId)).isZero();
    }

    @Test
    void holdCreationOnABlockedClosedUnknownOrTechnicalAccountFailsWithTheMatchingCode() {
        Funded blocked = fundedCustomer(10_000);
        var blocking = http().post().uri("/v1/accounts/{id}/block", blocked.accountId())
                .header(AUTHORIZATION, operator("op-1")).header(IDEMPOTENCY_KEY, newIdempotencyKey())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "OPERATOR"))
                .retrieve().toEntity(JsonNode.class);
        assertThat(blocking.getStatusCode().value()).isEqualTo(200);
        Funded closed = fundedCustomer(10_000);
        jdbc.sql("UPDATE accounts SET status = 'CLOSED', closed_at = now() WHERE account_id = ?")
                .param(closed.accountId()).update();
        String technical = jdbc.sql("SELECT iban FROM accounts WHERE account_id = ?")
                .param(TechnicalAccounts.CSM_SETTLEMENT_ID).query(String.class).single();

        assertProblem(createHold(UUID.randomUUID(), blocked.iban(), 100), 422, "ACCOUNT_BLOCKED");
        assertProblem(createHold(UUID.randomUUID(), closed.iban(), 100), 422, "ACCOUNT_CLOSED");
        assertProblem(createHold(UUID.randomUUID(), "DE89370400440532013000", 100), 422, "ACCOUNT_NOT_FOUND");
        assertProblem(createHold(UUID.randomUUID(), technical, 100), 422, "ACCOUNT_NOT_CUSTOMER");
        assertProblem(createHold(UUID.randomUUID(), blocked.iban(), 100, "USD"), 422, "CURRENCY_NOT_SUPPORTED");
        assertBalances(blocked.accountId(), 10_000, 10_000);
    }

    @Test
    void aCreateRetriedUnderAnotherKeyReturnsTheExistingHoldWith200AndOneForAnotherAmountIsAConflict() {
        Funded payer = fundedCustomer(10_000);
        UUID holdId = UUID.randomUUID();
        String key = "hold-create:" + holdId;

        UUID paymentId = UUID.randomUUID();

        var first = createHold(key, holdId, payer.iban(), 2_000, "EUR", paymentId, "TPE-TX-1");
        var replayed = createHold(key, holdId, payer.iban(), 2_000, "EUR", paymentId, "TPE-TX-1");
        var retried = createHold(newIdempotencyKey(), holdId, payer.iban(), 2_000, "EUR", paymentId, "TPE-TX-1");
        var otherAmount = createHold(newIdempotencyKey(), holdId, payer.iban(), 2_001, "EUR", paymentId, "TPE-TX-1");

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(replayed.getStatusCode().value()).isEqualTo(201);
        assertThat(replayed.getBody()).isEqualTo(first.getBody());
        assertThat(retried.getStatusCode().value()).isEqualTo(200);
        assertThat(retried.getBody()).isEqualTo(first.getBody());
        assertProblem(otherAmount, 409, "IDEMPOTENCY_CONFLICT");
        assertBalances(payer.accountId(), 10_000, 8_000);
    }

    @Test
    void releasingTwiceReleasesOnce() {
        Funded payer = fundedCustomer(10_000);
        UUID holdId = UUID.randomUUID();
        createHold(holdId, payer.iban(), 1_500);

        var first = release(holdId);
        var second = release(holdId);

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getBody().get("status").asString()).isEqualTo("RELEASED");
        assertBalances(payer.accountId(), 10_000, 10_000);
    }

    @Test
    void aHoldIsReleasedAlsoAfterItsAccountWasBlocked() {
        Funded payer = fundedCustomer(10_000);
        UUID holdId = UUID.randomUUID();
        createHold(holdId, payer.iban(), 1_500);
        http().post().uri("/v1/accounts/{id}/block", payer.accountId())
                .header(AUTHORIZATION, operator("op-1")).header(IDEMPOTENCY_KEY, newIdempotencyKey())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "OPERATOR"))
                .retrieve().toBodilessEntity();

        assertThat(release(holdId).getStatusCode().value()).isEqualTo(200);
        assertBalances(payer.accountId(), 10_000, 10_000);
    }

    @Test
    void readingAHoldReturnsItAndAnUnknownIdIs404() {
        Funded payer = fundedCustomer(10_000);
        UUID holdId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        createHold(newIdempotencyKey(), holdId, payer.iban(), 700, "EUR", paymentId, "TPE-TX-47");

        JsonNode hold = get(holdId).getBody();
        var unknown = get(UUID.randomUUID());

        assertThat(hold.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(hold.get("paymentId").asString()).isEqualTo(paymentId.toString());
        assertThat(hold.get("txId").asString()).isEqualTo("TPE-TX-47");
        assertThat(hold.has("finalisedAt")).isFalse();
        assertProblem(unknown, 404, "HOLD_NOT_FOUND");
    }

    @Test
    void invalidRequestsAre400() {
        Funded payer = fundedCustomer(10_000);
        var missing = post("/v1/holds", newIdempotencyKey(), Map.of());
        var badIban = createHold(UUID.randomUUID(), "DE00XX", 100);
        var zero = createHold(UUID.randomUUID(), payer.iban(), 0);
        var noKey = post("/v1/holds", null, holdBody(UUID.randomUUID(), payer.iban(), 100, "EUR", null, null));

        assertProblem(missing, 400, "VALIDATION_FAILED");
        assertThat(missing.getBody().get("errors")).hasSize(4);
        assertProblem(badIban, 400, "VALIDATION_FAILED");
        assertProblem(zero, 400, "VALIDATION_FAILED");
        assertProblem(noKey, 400, "IDEMPOTENCY_KEY_MISSING");
        assertBalances(payer.accountId(), 10_000, 10_000);
    }

    /**
     * A latch in the database: the create and the release of the same new id each wait in a trigger on {@code holds}
     * for an advisory lock the test holds, so both have looked the id up and found nothing. Released together, the
     * first insert wins; the other waits for it, finds the id taken and re-reads. Either way the hold ends released and
     * nothing stays held.
     */
    @Test
    void aReleaseAndACreateRacingForANewIdAreDecidedByTheFirstInsert() throws Exception {
        Funded payer = fundedCustomer(10_000);
        UUID holdId = UUID.randomUUID();
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION latched_hold() RETURNS trigger AS $$
                BEGIN
                  IF NEW.hold_id = '%s' THEN
                    PERFORM pg_advisory_xact_lock_shared(%d);
                  END IF;
                  RETURN NEW;
                END $$ LANGUAGE plpgsql""".formatted(holdId, LATCH)).update();
        jdbc.sql("CREATE TRIGGER latched_hold BEFORE INSERT ON holds FOR EACH ROW EXECUTE FUNCTION latched_hold()")
                .update();
        ResponseEntity<JsonNode> created;
        ResponseEntity<JsonNode> released;
        try (Connection latch = dataSource.getConnection(); var threads = Executors.newFixedThreadPool(2)) {
            latch.createStatement().execute("SELECT pg_advisory_lock(" + LATCH + ")");
            Future<ResponseEntity<JsonNode>> create = threads.submit(() -> createHold(holdId, payer.iban(), 2_500));
            Future<ResponseEntity<JsonNode>> release = threads.submit(() -> release(holdId));
            await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql("""
                    SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND objid = ? AND NOT granted""")
                    .param(LATCH).query(Long.class).single() == 2);
            latch.createStatement().execute("SELECT pg_advisory_unlock(" + LATCH + ")");
            created = create.get();
            released = release.get();
        } finally {
            jdbc.sql("DROP TRIGGER latched_hold ON holds").update();
            jdbc.sql("DROP FUNCTION latched_hold()").update();
        }

        assertThat(released.getStatusCode().value()).isEqualTo(200);
        assertThat(released.getBody().get("status").asString()).isEqualTo("RELEASED");
        if (released.getBody().get("tombstone").asBoolean()) {
            // the release inserted first: the create finds its tombstone
            assertProblem(created, 409, "HOLD_RELEASED");
        } else {
            // the create inserted first: the release releases the hold it left
            assertThat(created.getStatusCode().value()).isEqualTo(201);
        }
        assertThat(holdRows(holdId)).isOne();
        assertThat(get(holdId).getBody().get("status").asString()).isEqualTo("RELEASED");
        assertBalances(payer.accountId(), 10_000, 10_000);
    }

    /** 12 §8: version-checked writes of {@code held_minor} lose no update. */
    @Test
    void fiftyConcurrentHoldsOnOneAccountNeverTakeAvailableBelowZero() throws Exception {
        Funded payer = fundedCustomer(10_000);
        int holds = 50;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> calls = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(holds)) {
            for (int i = 0; i < holds; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return createHold(UUID.randomUUID(), payer.iban(), 300);
                }));
            }
            start.countDown();
            int createdCount = 0;
            for (var call : calls) {
                ResponseEntity<JsonNode> response = call.get();
                int status = response.getStatusCode().value();
                if (status == 201) {
                    createdCount++;
                } else {
                    assertThat(response.getBody().get("code").asString())
                            .isIn("INSUFFICIENT_FUNDS", "CONCURRENT_MODIFICATION");
                }
            }
            long active = jdbc.sql("SELECT count(*) FROM holds WHERE account_id = ? AND status = 'ACTIVE'")
                    .param(payer.accountId()).query(Long.class).single();
            long held = jdbc.sql("SELECT held_minor FROM accounts WHERE account_id = ?").param(payer.accountId())
                    .query(Long.class).single();
            assertThat(active).isEqualTo(createdCount);
            assertThat(held).isEqualTo(active * 300).isLessThanOrEqualTo(10_000);
            assertThat(createdCount).isPositive();
        }
    }

    @Test
    void tombstonesOlderThanTheRetentionAreDeletedAndOthersKept() {
        Funded payer = fundedCustomer(10_000);
        UUID expired = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        UUID oldActive = UUID.randomUUID();
        Instant old = CLOCK.instant().minus(Duration.ofDays(31));
        insertTombstone(expired, old);
        insertTombstone(recent, CLOCK.instant().minus(Duration.ofDays(29)));
        createHold(oldActive, payer.iban(), 100);
        jdbc.sql("UPDATE holds SET created_at = ? WHERE hold_id = ?").params(old.atOffset(ZoneOffset.UTC), oldActive)
                .update();

        tombstoneRetention.purge();

        assertThat(holdRows(expired)).isZero();
        assertThat(holdRows(recent)).isOne();
        assertThat(holdRows(oldActive)).isOne();
    }

    private void insertTombstone(UUID holdId, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO holds (hold_id, status, created_at, finalised_at, tombstone)
                VALUES (?, 'RELEASED', ?, ?, true)""")
                .params(holdId, createdAt.atOffset(ZoneOffset.UTC), createdAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private record Funded(UUID accountId, String iban) {}

    private Funded fundedCustomer(long amountMinor) {
        UUID accountId = openCustomer("HOLD-" + UUID.randomUUID().toString().substring(0, 8) + "-"
                + CUSTOMERS.incrementAndGet());
        var funded = fund(operator("op-1"), newIdempotencyKey(), accountId, eur(amountMinor));
        assertThat(funded.getStatusCode().value()).isEqualTo(200);
        String iban = jdbc.sql("SELECT iban FROM accounts WHERE account_id = ?").param(accountId)
                .query(String.class).single();
        return new Funded(accountId, iban);
    }

    private ResponseEntity<JsonNode> createHold(UUID holdId, String iban, long amountMinor) {
        return createHold(holdId, iban, amountMinor, "EUR");
    }

    private ResponseEntity<JsonNode> createHold(UUID holdId, String iban, long amountMinor, String currency) {
        return createHold("hold-create:" + holdId + ":" + UUID.randomUUID(), holdId, iban, amountMinor, currency);
    }

    private ResponseEntity<JsonNode> createHold(String key, UUID holdId, String iban, long amountMinor,
            String currency) {
        return createHold(key, holdId, iban, amountMinor, currency, UUID.randomUUID(), "TPE-TX-1");
    }

    private ResponseEntity<JsonNode> createHold(String key, UUID holdId, String iban, long amountMinor,
            String currency, UUID paymentId, String txId) {
        return post("/v1/holds", key, holdBody(holdId, iban, amountMinor, currency, paymentId, txId));
    }

    private static Map<String, Object> holdBody(UUID holdId, String iban, long amountMinor, String currency,
            UUID paymentId, String txId) {
        Map<String, Object> body = new HashMap<>();
        body.put("holdId", holdId.toString());
        body.put("debtorIban", iban);
        body.put("amountMinor", amountMinor);
        body.put("currency", currency);
        if (paymentId != null) {
            body.put("reference", Map.of("paymentId", paymentId.toString(), "txId", txId));
        }
        return body;
    }

    private ResponseEntity<JsonNode> release(UUID holdId) {
        return post("/v1/holds/" + holdId + "/release", "hold-release:" + holdId + ":" + UUID.randomUUID(),
                Map.of());
    }

    private ResponseEntity<JsonNode> post(String uri, String key, Map<String, ?> body) {
        var request = http().post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(body);
        if (key != null) {
            request.header(IDEMPOTENCY_KEY, key);
        }
        return request.retrieve().toEntity(JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(UUID holdId) {
        return http().get().uri("/v1/holds/{id}", holdId).retrieve().toEntity(JsonNode.class);
    }

    private long holdRows(UUID holdId) {
        return jdbc.sql("SELECT count(*) FROM holds WHERE hold_id = ?").param(holdId).query(Long.class).single();
    }

    private void assertBalances(UUID accountId, long booked, long available) {
        JsonNode account = http().get().uri("/v1/accounts/{id}", accountId).header(AUTHORIZATION, operator("op-1"))
                .retrieve().toEntity(JsonNode.class).getBody();
        assertThat(account.get("bookedBalanceMinor").asLong()).as("booked").isEqualTo(booked);
        assertThat(account.get("availableBalanceMinor").asLong()).as("available").isEqualTo(available);
    }

    private static void assertProblem(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().get("code").asString()).isEqualTo(code);
    }
}
