package com.tpe.ledgerservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.contracts.TechnicalAccounts;
import com.tpe.ledgerservice.application.reservation.TombstoneRetention;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Pending credits of the Posting API (specs/12-ledger-service.md §3.1, ADR-005, ADR-010) and their tombstones
 * (ADR-009). The Posting API is {@code @InternalApi}: no token until M5 (00 §12.1), so no request here carries one.
 */
class PendingCreditIT extends LedgerStack {

    /** The advisory lock of the latched races. */
    private static final long LATCH = 4_901;
    private static final String PEER_BIC = "PEERDEFFXXX";
    private static final AtomicInteger CUSTOMERS = new AtomicInteger();

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    TombstoneRetention tombstoneRetention;

    @Test
    void LED_AC_07_aPendingCreditChangesNeitherBalanceConfirmAddsToBothAndCancelRemovesTheMemo() {
        Customer payee = fundedCustomer(10_000);
        UUID confirmedId = UUID.randomUUID();
        UUID cancelledId = UUID.randomUUID();

        var created = create(confirmedId, payee.iban(), 5_000);
        create(cancelledId, payee.iban(), 2_000);

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(created.getBody().get("accountId").asString()).isEqualTo(payee.accountId().toString());
        assertBalances(payee.accountId(), 10_000, 10_000, 7_000);

        var cancelled = cancel(cancelledId);

        assertThat(cancelled.getStatusCode().value()).isEqualTo(200);
        assertThat(cancelled.getBody().get("status").asString()).isEqualTo("CANCELLED");
        assertBalances(payee.accountId(), 10_000, 10_000, 5_000);

        var confirmed = confirm(confirmedId);

        assertThat(confirmed.getStatusCode().value()).isEqualTo(200);
        JsonNode body = confirmed.getBody();
        assertThat(body.get("pendingCreditId").asString()).isEqualTo(confirmedId.toString());
        assertThat(body.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(body.get("accountId").asString()).isEqualTo(payee.accountId().toString());
        assertThat(body.get("confirmedAt").asString()).isNotBlank();
        assertBalances(payee.accountId(), 15_000, 15_000, 0);
        UUID entryId = UUID.fromString(body.get("journalEntryId").asString());
        assertThat(jdbc.sql("SELECT entry_type FROM journal_entries WHERE entry_id = ?").param(entryId)
                .query(String.class).single()).isEqualTo("PAYMENT_IN");
        assertThat(postings(entryId)).containsExactlyInAnyOrder(
                TechnicalAccounts.CSM_SETTLEMENT_ID + " DR 5000", payee.accountId() + " CR 5000");

        var repeated = confirm(confirmedId);

        assertThat(repeated.getStatusCode().value()).isEqualTo(200);
        assertThat(repeated.getBody().get("journalEntryId").asString()).isEqualTo(entryId.toString());
        assertBalances(payee.accountId(), 15_000, 15_000, 0);
    }

    @Test
    void LED_AC_08_creatingAPendingCreditOnABlockedClosedOrUnknownAccountFailsWithTheMatchingCode() {
        Customer blocked = fundedCustomer(0);
        block(blocked.accountId());
        Customer closed = fundedCustomer(0);
        jdbc.sql("UPDATE accounts SET status = 'CLOSED', closed_at = now() WHERE account_id = ?")
                .param(closed.accountId()).update();
        String technical = jdbc.sql("SELECT iban FROM accounts WHERE account_id = ?")
                .param(TechnicalAccounts.CSM_SETTLEMENT_ID).query(String.class).single();
        Customer open = fundedCustomer(0);
        UUID refusedId = UUID.randomUUID();

        assertProblem(create(refusedId, blocked.iban(), 100), 422, "ACCOUNT_BLOCKED");
        assertProblem(create(UUID.randomUUID(), closed.iban(), 100), 422, "ACCOUNT_CLOSED");
        assertProblem(create(UUID.randomUUID(), "DE89370400440532013000", 100), 422, "ACCOUNT_NOT_FOUND");
        assertProblem(create(UUID.randomUUID(), technical, 100), 422, "ACCOUNT_NOT_CUSTOMER");
        assertProblem(create(UUID.randomUUID(), open.iban(), 100, "USD"), 422, "CURRENCY_NOT_SUPPORTED");
        assertThat(rows(refusedId)).isZero();
        assertBalances(blocked.accountId(), 0, 0, 0);
    }

    /**
     * The block first: its transaction has updated the account row and waits in a trigger for a latch the test holds.
     * The pending-credit creation then waits for the row; once the block commits, it sees the block and refuses.
     */
    @Test
    void LED_AC_09_aBlockCommittingWhileAPendingCreditIsCreatedIsSeenByTheCreation() throws Exception {
        Customer payee = fundedCustomer(0);
        UUID id = UUID.randomUUID();
        latch("accounts", "AFTER UPDATE", "account_id", payee.accountId());
        ResponseEntity<JsonNode> blocking;
        ResponseEntity<JsonNode> created;
        // the latch closes first, so a failed wait never leaves the threads stuck on it
        try (var threads = Executors.newFixedThreadPool(2); Connection latch = dataSource.getConnection()) {
            latch.createStatement().execute("SELECT pg_advisory_lock(" + LATCH + ")");
            Future<ResponseEntity<JsonNode>> block = threads.submit(() -> block(payee.accountId()));
            awaitWaiting("advisory", 1);
            Future<ResponseEntity<JsonNode>> create = threads.submit(() -> create(id, payee.iban(), 1_000));
            awaitWaiting("transactionid", 1);
            latch.createStatement().execute("SELECT pg_advisory_unlock(" + LATCH + ")");
            blocking = block.get();
            created = create.get();
        } finally {
            dropLatch("accounts");
        }

        assertThat(blocking.getStatusCode().value()).isEqualTo(200);
        assertProblem(created, 422, "ACCOUNT_BLOCKED");
        assertThat(rows(id)).isZero();
    }

    /**
     * The creation first: it has read the account row {@code FOR SHARE} and waits in a trigger on
     * {@code pending_credits}. The block then waits for the row and commits only after the pending credit has.
     */
    @Test
    void LED_AC_09_aBlockStartedWhileAPendingCreditIsCreatedCommitsAfterIt() throws Exception {
        Customer payee = fundedCustomer(0);
        UUID id = UUID.randomUUID();
        latch("pending_credits", "BEFORE INSERT", "pending_credit_id", id);
        ResponseEntity<JsonNode> blocking;
        ResponseEntity<JsonNode> created;
        // the latch closes first, so a failed wait never leaves the threads stuck on it
        try (var threads = Executors.newFixedThreadPool(2); Connection latch = dataSource.getConnection()) {
            latch.createStatement().execute("SELECT pg_advisory_lock(" + LATCH + ")");
            Future<ResponseEntity<JsonNode>> create = threads.submit(() -> create(id, payee.iban(), 1_000));
            awaitWaiting("advisory", 1);
            Future<ResponseEntity<JsonNode>> block = threads.submit(() -> block(payee.accountId()));
            awaitWaiting("transactionid", 1);
            assertThat(block.isDone()).as("the block waits for the pending credit").isFalse();
            latch.createStatement().execute("SELECT pg_advisory_unlock(" + LATCH + ")");
            created = create.get();
            blocking = block.get();
        } finally {
            dropLatch("pending_credits");
        }

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(blocking.getStatusCode().value()).isEqualTo(200);
        assertThat(get(id).getBody().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("SELECT status FROM accounts WHERE account_id = ?").param(payee.accountId())
                .query(String.class).single()).isEqualTo("BLOCKED");
    }

    /** The pending-credit part of LED-AC-17; the open and hold parts are in AccountApiIT and HoldIT. */
    @Test
    void LED_AC_17_aCustomerAccountWithoutCustomerIdMakesPendingCreditCreationFailCustomerIdMissing() {
        Customer payee = fundedCustomer(0);
        String constraint = jdbc.sql("""
                SELECT conname FROM pg_constraint
                 WHERE conrelid = 'accounts'::regclass AND pg_get_constraintdef(oid) LIKE '%customer_id IS NOT NULL%'""")
                .query(String.class).single();
        // a row the constraint never saw, as after a migration that added it NOT VALID (12 §5)
        jdbc.sql("ALTER TABLE accounts DROP CONSTRAINT " + constraint).update();
        try {
            jdbc.sql("UPDATE accounts SET customer_id = NULL WHERE account_id = ?").param(payee.accountId()).update();
        } finally {
            jdbc.sql("ALTER TABLE accounts ADD CONSTRAINT " + constraint
                    + " CHECK (kind = 'TECHNICAL' OR customer_id IS NOT NULL) NOT VALID").update();
        }
        UUID id = UUID.randomUUID();

        var refused = create(id, payee.iban(), 100);

        assertProblem(refused, 422, "CUSTOMER_ID_MISSING");
        assertThat(rows(id)).isZero();
    }

    @Test
    void cancellingAnUnknownPendingCreditCreatesATombstoneAndALaterCreateWithThatIdFailsPendingCreditCancelled() {
        Customer payee = fundedCustomer(0);
        UUID id = UUID.randomUUID();

        var cancelled = cancel(id);

        assertThat(cancelled.getStatusCode().value()).isEqualTo(200);
        assertThat(cancelled.getBody().get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.getBody().get("tombstone").asBoolean()).isTrue();
        assertThat(cancelled.getBody().has("accountId")).isFalse();
        assertThat(cancelled.getBody().has("amountMinor")).isFalse();

        var lateCreate = create(id, payee.iban(), 1_000);

        assertProblem(lateCreate, 409, "PENDING_CREDIT_CANCELLED");
        assertProblem(confirm(id), 409, "PENDING_CREDIT_CANCELLED");
        assertBalances(payee.accountId(), 0, 0, 0);
        assertThat(get(id).getBody().get("tombstone").asBoolean()).isTrue();
    }

    @Test
    void aConfirmSucceedsOnAPayeeBlockedAfterThePendingCreditWasCreated() {
        Customer payee = fundedCustomer(1_000);
        UUID id = UUID.randomUUID();
        create(id, payee.iban(), 4_000);
        block(payee.accountId());

        var confirmed = confirm(id);

        assertThat(confirmed.getStatusCode().value()).isEqualTo(200);
        assertThat(confirmed.getBody().get("status").asString()).isEqualTo("CONFIRMED");
        assertBalances(payee.accountId(), 5_000, 5_000, 0);
    }

    @Test
    void aConfirmedCreditCannotBeCancelledACancelledOneCannotBeConfirmedAndAnUnknownOneIsNotFound() {
        Customer payee = fundedCustomer(0);
        UUID confirmedId = UUID.randomUUID();
        UUID cancelledId = UUID.randomUUID();
        create(confirmedId, payee.iban(), 300);
        create(cancelledId, payee.iban(), 200);
        confirm(confirmedId);
        cancel(cancelledId);

        assertProblem(cancel(confirmedId), 409, "PENDING_CREDIT_CONFIRMED");
        assertProblem(confirm(cancelledId), 409, "PENDING_CREDIT_CANCELLED");
        assertProblem(create(confirmedId, payee.iban(), 300), 409, "PENDING_CREDIT_CONFIRMED");
        assertProblem(confirm(UUID.randomUUID()), 404, "PENDING_CREDIT_NOT_FOUND");
        assertThat(cancel(cancelledId).getStatusCode().value()).isEqualTo(200);
        assertBalances(payee.accountId(), 300, 300, 0);
    }

    @Test
    void aCreateRetriedUnderAnotherKeyReturnsTheExistingCreditWith200AndOneForAnotherAmountIsAConflict() {
        Customer payee = fundedCustomer(0);
        UUID id = UUID.randomUUID();
        String key = "pending-credit-create:" + id;

        var first = create(key, id, payee.iban(), 2_000, "EUR");
        var replayed = create(key, id, payee.iban(), 2_000, "EUR");
        var retried = create(newIdempotencyKey(), id, payee.iban(), 2_000, "EUR");
        var otherAmount = create(newIdempotencyKey(), id, payee.iban(), 2_001, "EUR");

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(replayed.getStatusCode().value()).isEqualTo(201);
        assertThat(replayed.getBody()).isEqualTo(first.getBody());
        assertThat(retried.getStatusCode().value()).isEqualTo(200);
        assertThat(retried.getBody()).isEqualTo(first.getBody());
        assertProblem(otherAmount, 409, "IDEMPOTENCY_CONFLICT");
        assertBalances(payee.accountId(), 0, 0, 2_000);
    }

    @Test
    void readingAPendingCreditReturnsItsReferenceAndAnUnknownIdIs404() {
        Customer payee = fundedCustomer(0);
        UUID id = UUID.randomUUID();
        create(id, payee.iban(), 700);

        JsonNode credit = get(id).getBody();

        assertThat(credit.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(credit.get("amountMinor").asLong()).isEqualTo(700);
        assertThat(credit.get("paymentId").asString()).isEqualTo(id.toString());
        assertThat(credit.get("txId").asString()).isEqualTo("PEER-TX-" + id.toString().substring(0, 8));
        assertThat(credit.get("debtorAgentBic").asString()).isEqualTo(PEER_BIC);
        assertThat(credit.get("tombstone").asBoolean()).isFalse();
        assertThat(credit.has("finalisedAt")).isFalse();
        assertProblem(get(UUID.randomUUID()), 404, "PENDING_CREDIT_NOT_FOUND");
    }

    /** ADR-010: the id is the incoming paymentId, so a reference without one still books the entry with it. */
    @Test
    void aReferenceWithoutPaymentIdTakesThePendingCreditIdAndItsConfirmBooksTheEntryWithIt() {
        Customer payee = fundedCustomer(0);
        UUID id = UUID.randomUUID();
        Map<String, Object> body = body(id, payee.iban(), 900, "EUR");
        body.remove("reference");
        post("/v1/pending-credits", newIdempotencyKey(), body);

        UUID entryId = UUID.fromString(confirm(id).getBody().get("journalEntryId").asString());

        assertThat(get(id).getBody().get("paymentId").asString()).isEqualTo(id.toString());
        assertThat(jdbc.sql("SELECT payment_id FROM journal_entries WHERE entry_id = ?").param(entryId)
                .query(UUID.class).single()).isEqualTo(id);
    }

    @Test
    void invalidRequestsAre400() {
        Customer payee = fundedCustomer(0);
        UUID id = UUID.randomUUID();
        Map<String, Object> badBic = body(id, payee.iban(), 100, "EUR");
        badBic.put("reference", Map.of("paymentId", id.toString(), "txId", "T", "debtorAgentBic", "NOT A BIC"));
        Map<String, Object> longTxId = body(id, payee.iban(), 100, "EUR");
        longTxId.put("reference", Map.of("paymentId", id.toString(), "txId", "T".repeat(36)));
        Map<String, Object> otherPayment = body(id, payee.iban(), 100, "EUR");
        otherPayment.put("reference", Map.of("paymentId", UUID.randomUUID().toString()));

        var missing = post("/v1/pending-credits", newIdempotencyKey(), Map.of());
        var mismatch = post("/v1/pending-credits", newIdempotencyKey(), otherPayment);

        assertProblem(missing, 400, "VALIDATION_FAILED");
        assertThat(missing.getBody().get("errors")).hasSize(4);
        assertProblem(mismatch, 400, "VALIDATION_FAILED");
        assertThat(mismatch.getBody().get("errors").get(0).get("code").asString()).isEqualTo("NOT_PENDING_CREDIT_ID");
        assertProblem(post("/v1/pending-credits", newIdempotencyKey(), badBic), 400, "VALIDATION_FAILED");
        assertProblem(post("/v1/pending-credits", newIdempotencyKey(), longTxId), 400, "VALIDATION_FAILED");
        assertProblem(create(UUID.randomUUID(), payee.iban(), 0), 400, "VALIDATION_FAILED");
        assertProblem(post("/v1/pending-credits", null, body(id, payee.iban(), 100, "EUR")), 400,
                "IDEMPOTENCY_KEY_MISSING");
        assertThat(rows(id)).isZero();
    }

    /**
     * The create and the cancel of the same new id each wait in a trigger on {@code pending_credits}, both having
     * looked the id up and found nothing. Released together, the first insert wins; either way the credit ends
     * cancelled.
     */
    @Test
    void aCancelAndACreateRacingForANewIdAreDecidedByTheFirstInsert() throws Exception {
        Customer payee = fundedCustomer(0);
        UUID id = UUID.randomUUID();
        latch("pending_credits", "BEFORE INSERT", "pending_credit_id", id);
        ResponseEntity<JsonNode> created;
        ResponseEntity<JsonNode> cancelled;
        // the latch closes first, so a failed wait never leaves the threads stuck on it
        try (var threads = Executors.newFixedThreadPool(2); Connection latch = dataSource.getConnection()) {
            latch.createStatement().execute("SELECT pg_advisory_lock(" + LATCH + ")");
            Future<ResponseEntity<JsonNode>> create = threads.submit(() -> create(id, payee.iban(), 2_500));
            Future<ResponseEntity<JsonNode>> cancel = threads.submit(() -> cancel(id));
            awaitWaiting("advisory", 2);
            latch.createStatement().execute("SELECT pg_advisory_unlock(" + LATCH + ")");
            created = create.get();
            cancelled = cancel.get();
        } finally {
            dropLatch("pending_credits");
        }

        assertThat(cancelled.getStatusCode().value()).isEqualTo(200);
        assertThat(cancelled.getBody().get("status").asString()).isEqualTo("CANCELLED");
        if (cancelled.getBody().get("tombstone").asBoolean()) {
            assertProblem(created, 409, "PENDING_CREDIT_CANCELLED");
        } else {
            assertThat(created.getStatusCode().value()).isEqualTo(201);
        }
        assertThat(rows(id)).isOne();
        assertThat(get(id).getBody().get("status").asString()).isEqualTo("CANCELLED");
        assertBalances(payee.accountId(), 0, 0, 0);
    }

    @Test
    void pendingCreditTombstonesOlderThanTheRetentionAreDeletedAndOthersKept() {
        Customer payee = fundedCustomer(0);
        UUID expired = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        UUID oldActive = UUID.randomUUID();
        Instant old = CLOCK.instant().minus(Duration.ofDays(31));
        insertTombstone(expired, old);
        insertTombstone(recent, CLOCK.instant().minus(Duration.ofDays(29)));
        create(oldActive, payee.iban(), 100);
        jdbc.sql("UPDATE pending_credits SET created_at = ? WHERE pending_credit_id = ?")
                .params(old.atOffset(ZoneOffset.UTC), oldActive).update();

        tombstoneRetention.purge();

        assertThat(rows(expired)).isZero();
        assertThat(rows(recent)).isOne();
        assertThat(rows(oldActive)).isOne();
    }

    /**
     * A trigger on {@code table} that, for the row whose {@code column} is {@code value}, waits for {@link #LATCH}
     * inside the writing transaction.
     */
    private void latch(String table, String timing, String column, UUID value) {
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION latched_%s() RETURNS trigger AS $$
                BEGIN
                  IF NEW.%s = '%s' THEN
                    PERFORM pg_advisory_xact_lock_shared(%d);
                  END IF;
                  RETURN NEW;
                END $$ LANGUAGE plpgsql""".formatted(table, column, value, LATCH)).update();
        jdbc.sql("CREATE TRIGGER latched_%1$s %2$s ON %1$s FOR EACH ROW EXECUTE FUNCTION latched_%1$s()"
                .formatted(table, timing)).update();
    }

    private void dropLatch(String table) {
        jdbc.sql("DROP TRIGGER latched_%1$s ON %1$s".formatted(table)).update();
        jdbc.sql("DROP FUNCTION latched_%s()".formatted(table)).update();
    }

    /** Until {@code count} backends wait for a lock of the kind {@code waitEvent} ({@code advisory}, a row's writer). */
    private void awaitWaiting(String waitEvent, int count) {
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql("""
                SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND wait_event = ?""")
                .param(waitEvent).query(Long.class).single() >= count);
    }

    private void insertTombstone(UUID id, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO pending_credits (pending_credit_id, status, created_at, finalised_at, tombstone)
                VALUES (?, 'CANCELLED', ?, ?, true)""")
                .params(id, createdAt.atOffset(ZoneOffset.UTC), createdAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private record Customer(UUID accountId, String iban) {}

    private Customer fundedCustomer(long amountMinor) {
        UUID accountId = openCustomer("PC-" + UUID.randomUUID().toString().substring(0, 8) + "-"
                + CUSTOMERS.incrementAndGet());
        if (amountMinor > 0) {
            var funded = fund(operator("op-1"), newIdempotencyKey(), accountId, eur(amountMinor));
            assertThat(funded.getStatusCode().value()).isEqualTo(200);
        }
        String iban = jdbc.sql("SELECT iban FROM accounts WHERE account_id = ?").param(accountId)
                .query(String.class).single();
        return new Customer(accountId, iban);
    }

    private ResponseEntity<JsonNode> block(UUID accountId) {
        return http().post().uri("/v1/accounts/{id}/block", accountId)
                .header(AUTHORIZATION, operator("op-1")).header(IDEMPOTENCY_KEY, newIdempotencyKey())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "OPERATOR"))
                .retrieve().toEntity(JsonNode.class);
    }

    private ResponseEntity<JsonNode> create(UUID id, String iban, long amountMinor) {
        return create(id, iban, amountMinor, "EUR");
    }

    private ResponseEntity<JsonNode> create(UUID id, String iban, long amountMinor, String currency) {
        return create("pending-credit-create:" + id + ":" + UUID.randomUUID(), id, iban, amountMinor, currency);
    }

    private ResponseEntity<JsonNode> create(String key, UUID id, String iban, long amountMinor, String currency) {
        return post("/v1/pending-credits", key, body(id, iban, amountMinor, currency));
    }

    private static Map<String, Object> body(UUID id, String iban, long amountMinor, String currency) {
        Map<String, Object> body = new HashMap<>();
        body.put("pendingCreditId", id.toString());
        body.put("creditorIban", iban);
        body.put("amountMinor", amountMinor);
        body.put("currency", currency);
        body.put("reference", Map.of("paymentId", id.toString(), "txId", "PEER-TX-" + id.toString().substring(0, 8),
                "debtorAgentBic", PEER_BIC));
        return body;
    }

    private ResponseEntity<JsonNode> confirm(UUID id) {
        return post("/v1/pending-credits/" + id + "/confirm", "pending-credit-confirm:" + id + ":" + UUID.randomUUID(),
                Map.of());
    }

    private ResponseEntity<JsonNode> cancel(UUID id) {
        return post("/v1/pending-credits/" + id + "/cancel", "pending-credit-cancel:" + id + ":" + UUID.randomUUID(),
                Map.of());
    }

    private ResponseEntity<JsonNode> post(String uri, String key, Map<String, ?> body) {
        var request = http().post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(body);
        if (key != null) {
            request.header(IDEMPOTENCY_KEY, key);
        }
        return request.retrieve().toEntity(JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(UUID id) {
        return http().get().uri("/v1/pending-credits/{id}", id).retrieve().toEntity(JsonNode.class);
    }

    private long rows(UUID id) {
        return jdbc.sql("SELECT count(*) FROM pending_credits WHERE pending_credit_id = ?").param(id)
                .query(Long.class).single();
    }

    private List<String> postings(UUID entryId) {
        return jdbc.sql("SELECT account_id || ' ' || side || ' ' || amount_minor FROM postings WHERE entry_id = ?")
                .param(entryId).query(String.class).list();
    }

    private void assertBalances(UUID accountId, long booked, long available, long pending) {
        JsonNode account = http().get().uri("/v1/accounts/{id}", accountId).header(AUTHORIZATION, operator("op-1"))
                .retrieve().toEntity(JsonNode.class).getBody();
        assertThat(account.get("bookedBalanceMinor").asLong()).as("booked").isEqualTo(booked);
        assertThat(account.get("availableBalanceMinor").asLong()).as("available").isEqualTo(available);
        assertThat(account.get("pendingCreditsMinor").asLong()).as("pending credits").isEqualTo(pending);
    }

    private static void assertProblem(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().get("code").asString()).isEqualTo(code);
    }
}
